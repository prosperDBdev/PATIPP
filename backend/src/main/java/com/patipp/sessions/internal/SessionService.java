package com.patipp.sessions.internal;

import com.patipp.attempts.domain.QuestionAttempt;
import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.common.error.BadRequestException;
import com.patipp.common.error.ConflictException;
import com.patipp.common.error.NotFoundException;
import com.patipp.common.security.CurrentUser;
import com.patipp.preparations.api.BlueprintMerger;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.preparations.domain.PreparationSpace;
import com.patipp.questions.api.QuestionAccess;
import com.patipp.questions.api.QuestionAccess.SelectionFilters;
import com.patipp.questions.api.QuestionAccess.ServedQuestion;
import com.patipp.questions.domain.content.Answer;
import com.patipp.questions.domain.content.EvaluationResult;
import com.patipp.sessions.api.SessionDtos.AnswerResult;
import com.patipp.sessions.api.SessionDtos.ReviewItem;
import com.patipp.sessions.api.SessionDtos.ServedItem;
import com.patipp.sessions.api.SessionDtos.SessionAvailability;
import com.patipp.sessions.api.SessionDtos.SessionListEntry;
import com.patipp.sessions.api.SessionDtos.SessionResponse;
import com.patipp.sessions.api.SessionDtos.SessionSummary;
import com.patipp.sessions.api.SessionDtos.StartSessionRequest;
import com.patipp.sessions.api.SessionDtos.SubmitAnswerRequest;
import com.patipp.sessions.domain.ItemState;
import com.patipp.sessions.domain.SessionItem;
import com.patipp.sessions.domain.SessionItemRepository;
import com.patipp.sessions.domain.SessionMode;
import com.patipp.sessions.domain.SessionStatus;
import com.patipp.sessions.domain.StudySession;
import com.patipp.sessions.domain.StudySessionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The session engine.
 *
 * <p>Everything here is mode-agnostic: selecting questions, serving them in order, recording
 * an immutable attempt, updating counters, computing a score. The few decisions that genuinely
 * differ between practice, exams and interviews are delegated to a {@link SessionModeHandler}.
 *
 * <p>That split is the point of Phase 3. Exam mode in Phase 4 is a new handler, not a second
 * copy of this class - which is what stops the application growing three parallel attempt
 * logs and three sets of analytics.
 */
@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final StudySessionRepository sessions;
    private final SessionItemRepository items;
    private final QuestionAttemptRepository attempts;
    private final QuestionAccess questionAccess;
    private final SessionModeRegistry modes;
    private final SpaceAccessGuard accessGuard;
    private final BlueprintMerger blueprintMerger;
    private final CurrentUser currentUser;
    private final Clock clock;

    public SessionService(StudySessionRepository sessions,
                          SessionItemRepository items,
                          QuestionAttemptRepository attempts,
                          QuestionAccess questionAccess,
                          SessionModeRegistry modes,
                          SpaceAccessGuard accessGuard,
                          BlueprintMerger blueprintMerger,
                          CurrentUser currentUser,
                          Clock clock) {
        this.sessions = sessions;
        this.items = items;
        this.attempts = attempts;
        this.questionAccess = questionAccess;
        this.modes = modes;
        this.accessGuard = accessGuard;
        this.blueprintMerger = blueprintMerger;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ starting

    /** How many questions match these filters, so the user knows before committing. */
    @Transactional(readOnly = true)
    public SessionAvailability availability(UUID spaceId, StartSessionRequest request) {
        PreparationSpace space = accessGuard.requireOwned(spaceId);
        int available = questionAccess.countAvailable(spaceId, filtersFrom(request));
        int suggested = modes.require(SessionMode.PRACTICE)
                .resolveLength(configFrom(request), effectiveSettings(space));
        return new SessionAvailability(available, Math.min(suggested, available));
    }

    @Transactional
    public SessionResponse start(UUID spaceId, StartSessionRequest request) {
        PreparationSpace space = accessGuard.requireWritable(spaceId);
        UUID userId = currentUser.requireId();

        SessionMode mode = parseMode(request.mode());
        SessionModeHandler handler = modes.require(mode);

        // One live session per space at a time. Two open sessions would split a single study
        // period across two histories and make the counters meaningless.
        sessions.findInProgress(userId, spaceId).stream().findFirst().ifPresent(existing -> {
            throw new ConflictException("session.already_in_progress",
                    "You already have a session in progress. Finish or abandon it first.");
        });

        Map<String, Object> config = configFrom(request);
        int length = handler.resolveLength(config, effectiveSettings(space));

        List<QuestionAccess.SelectedQuestion> selected =
                questionAccess.selectForSession(spaceId, filtersFrom(request), length);

        if (selected.isEmpty()) {
            throw new BadRequestException("session.no_questions",
                    "No questions match that selection. Add or import some questions first.");
        }

        Instant now = clock.instant();
        StudySession session = StudySession.start(userId, spaceId, mode, config,
                handler.deadlineFor(config, now));
        sessions.saveAndFlush(session);

        short position = 0;
        for (QuestionAccess.SelectedQuestion question : selected) {
            items.save(SessionItem.of(session.id(), spaceId, position++,
                    question.questionId(), question.questionVersionId(),
                    question.selectionReason()));
        }
        session.setTotalItems(selected.size());

        log.info("Started {} session {} with {} items in space {}",
                mode, session.id(), selected.size(), spaceId);

        return toResponse(session, handler);
    }

    // ------------------------------------------------------------------ reading

    @Transactional
    public SessionResponse get(UUID spaceId, UUID sessionId) {
        StudySession session = requireSession(spaceId, sessionId);
        return toResponse(session, modes.require(session.mode()));
    }

    /** The session left open in this space, if any, so the app can offer to resume it. */
    @Transactional(readOnly = true)
    public Optional<SessionListEntry> inProgress(UUID spaceId) {
        accessGuard.requireOwned(spaceId);
        return sessions.findInProgress(currentUser.requireId(), spaceId).stream()
                .findFirst()
                .map(this::toListEntry);
    }

    @Transactional(readOnly = true)
    public List<SessionListEntry> history(UUID spaceId, int page, int size) {
        accessGuard.requireOwned(spaceId);
        return sessions.findHistory(currentUser.requireId(), spaceId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 50)))
                .map(this::toListEntry)
                .toList();
    }

    // ------------------------------------------------------------------ answering

    /**
     * Grades one answer and records it permanently.
     *
     * <p>The attempt is written before the counters move, and both happen in one transaction:
     * a recorded answer that did not update the session, or a session that counted an answer
     * it never stored, would each corrupt the log every later phase is rebuilt from.
     */
    @Transactional
    public AnswerResult answer(UUID spaceId, UUID sessionId, SubmitAnswerRequest request) {
        StudySession session = requireSession(spaceId, sessionId);
        SessionModeHandler handler = modes.require(session.mode());

        if (session.isFinished()) {
            throw new ConflictException("session.finished",
                    "That session has already been submitted.");
        }

        short position = request.position().shortValue();
        SessionItem item = items.findAt(sessionId, position)
                .orElseThrow(() -> new NotFoundException("session.item_not_found",
                        "There is no question at that position in this session."));

        if (item.isAnswered()) {
            // Practice moves forward only. Re-answering would either overwrite history or
            // append a second attempt for the same slot; neither is what a double-submitted
            // form means.
            throw new ConflictException("session.already_answered",
                    "That question has already been answered.");
        }

        ServedQuestion question = questionAccess.load(spaceId, item.questionId())
                .orElseThrow(() -> new NotFoundException("question.not_found",
                        "That question is no longer available."));

        Answer answer = Answer.parse(question.type(), request.answer());
        EvaluationResult result = question.content().evaluate(answer);

        Instant now = clock.instant();
        UUID userId = currentUser.requireId();
        int priorAttempts = attempts.countPriorAttempts(userId, spaceId, item.questionId());

        QuestionAttempt attempt = attempts.save(QuestionAttempt.record(
                userId, spaceId, item.questionId(),
                // The version served, not the current one. If the question was edited while
                // this session was open, the score still refers to what was actually shown.
                item.questionVersionId(),
                sessionId, question.subjectId(), question.topicId(), question.difficulty(),
                session.mode().name(), request.answer(),
                result.correct(), BigDecimal.valueOf(result.score()).setScale(3, RoundingMode.HALF_UP),
                gradeFrom(answer), request.responseTimeMs(),
                request.confidence() == null ? null : request.confidence().shortValue(),
                priorAttempts + 1, request.clientAttemptId()));

        item.markAnswered(attempt.id(), request.responseTimeMs() == null ? 0 : request.responseTimeMs());
        session.recordAnswer(result.correct(),
                request.responseTimeMs() == null ? 0 : request.responseTimeMs());
        questionAccess.recordAnswered(item.questionId(), result.correct(), request.responseTimeMs());

        boolean complete = session.answeredCount() >= session.totalItems();
        boolean reveal = handler.revealsFeedbackImmediately(session);

        return new AnswerResult(
                position,
                result.correct(),
                result.score(),
                reveal ? result.note() : null,
                reveal ? question.explanation() : null,
                reveal ? question.content().correctAnswer() : null,
                session.answeredCount(),
                session.totalItems(),
                complete,
                complete ? null : nextItem(session).orElse(null));
    }

    // ------------------------------------------------------------------ finishing

    @Transactional
    public SessionSummary complete(UUID spaceId, UUID sessionId) {
        StudySession session = requireSession(spaceId, sessionId);

        if (!session.isFinished()) {
            List<SessionItem> all = items.findForSession(sessionId);
            long answered = all.stream().filter(SessionItem::isAnswered).count();

            // Submitting early is allowed; unanswered questions simply do not count towards
            // the score. Scoring them as wrong would punish stopping, which is not something
            // to discourage in practice.
            session.finish(
                    answered == 0 ? SessionStatus.ABANDONED : SessionStatus.SUBMITTED,
                    clock.instant(),
                    scoreOf(session),
                    breakdownOf(sessionId));
        }

        return summaryOf(spaceId, session);
    }

    @Transactional(readOnly = true)
    public SessionSummary summary(UUID spaceId, UUID sessionId) {
        return summaryOf(spaceId, requireSession(spaceId, sessionId));
    }

    @Transactional
    public void abandon(UUID spaceId, UUID sessionId) {
        StudySession session = requireSession(spaceId, sessionId);
        if (!session.isFinished()) {
            // The attempts already recorded still count. Abandoning discards the sitting,
            // never the answers, because those are evidence of what was practised.
            session.finish(SessionStatus.ABANDONED, clock.instant(), scoreOf(session),
                    breakdownOf(sessionId));
        }
    }

    // ------------------------------------------------------------------ internals

    private StudySession requireSession(UUID spaceId, UUID sessionId) {
        accessGuard.requireOwned(spaceId);
        return sessions.findOwned(sessionId, spaceId, currentUser.requireId())
                .orElseThrow(() -> new NotFoundException("session.not_found", "No such session."));
    }

    private Optional<ServedItem> nextItem(StudySession session) {
        return items.findNextUnanswered(session.id()).flatMap(item -> {
            item.markViewed(clock.instant());
            return questionAccess.load(session.preparationSpaceId(), item.questionId())
                    .map(question -> toServedItem(item, question));
        });
    }

    /**
     * The answer key is stripped here, not in the browser.
     *
     * <p>Sending the full payload and hiding it in the interface would put every answer in the
     * page source, where the person most likely to look is the one whose own results it
     * invalidates.
     */
    private ServedItem toServedItem(SessionItem item, ServedQuestion question) {
        return new ServedItem(
                item.position(),
                question.questionId(),
                question.type().name(),
                question.difficulty(),
                question.subjectId(),
                question.topicId(),
                question.estimatedSeconds(),
                question.stem(),
                question.hints(),
                question.content().presentation());
    }

    private SessionResponse toResponse(StudySession session, SessionModeHandler handler) {
        ServedItem current = session.isFinished() ? null : nextItem(session).orElse(null);

        return new SessionResponse(
                session.id(),
                session.mode().name(),
                session.status().name(),
                session.startedAt(),
                session.deadlineAt(),
                session.submittedAt(),
                session.totalItems(),
                session.answeredCount(),
                session.correctCount(),
                session.activeMs(),
                handler.revealsFeedbackImmediately(session),
                current);
    }

    private SessionListEntry toListEntry(StudySession session) {
        return new SessionListEntry(
                session.id(), session.mode().name(), session.status().name(),
                session.startedAt(), session.submittedAt(), session.totalItems(),
                session.answeredCount(), session.correctCount(),
                session.score() == null ? null : session.score().doubleValue());
    }

    /** Percentage of answered questions that were correct. Unanswered ones are excluded. */
    private BigDecimal scoreOf(StudySession session) {
        if (session.answeredCount() == 0) {
            return null;
        }
        return BigDecimal.valueOf(session.correctCount() * 100.0 / session.answeredCount())
                .setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * Per-topic and per-difficulty results, computed from the attempts of this session.
     *
     * <p>Derived from the log rather than accumulated as we go, so it stays correct if the
     * session was resumed, and so the same computation can be replayed in Phase 7.
     */
    private Map<String, Object> breakdownOf(UUID sessionId) {
        List<QuestionAttempt> recorded = attempts.findForSession(sessionId);

        Map<String, int[]> byDifficulty = new LinkedHashMap<>();
        Map<String, int[]> byTopic = new LinkedHashMap<>();

        for (QuestionAttempt attempt : recorded) {
            tally(byDifficulty, attempt.difficulty(), attempt.isCorrect());
            tally(byTopic, attempt.topicId() == null ? "untagged" : attempt.topicId().toString(),
                    attempt.isCorrect());
        }

        Map<String, Object> breakdown = new LinkedHashMap<>();
        breakdown.put("byDifficulty", percentages(byDifficulty));
        breakdown.put("byTopic", percentages(byTopic));
        breakdown.put("totalAnswered", recorded.size());
        return breakdown;
    }

    private void tally(Map<String, int[]> target, String key, boolean correct) {
        int[] counts = target.computeIfAbsent(key, ignored -> new int[2]);
        counts[0]++;
        if (correct) {
            counts[1]++;
        }
    }

    private Map<String, Object> percentages(Map<String, int[]> tallies) {
        Map<String, Object> result = new LinkedHashMap<>();
        tallies.forEach((key, counts) -> result.put(key, Map.of(
                "answered", counts[0],
                "correct", counts[1],
                "percent", counts[0] == 0 ? 0
                        : BigDecimal.valueOf(counts[1] * 100.0 / counts[0])
                                .setScale(1, RoundingMode.HALF_UP).doubleValue())));
        return result;
    }

    private SessionSummary summaryOf(UUID spaceId, StudySession session) {
        List<SessionItem> all = items.findForSession(session.id());
        Map<UUID, QuestionAttempt> byQuestion = new HashMap<>();
        attempts.findForSession(session.id())
                .forEach(attempt -> byQuestion.put(attempt.questionId(), attempt));

        List<ReviewItem> review = new ArrayList<>();
        for (SessionItem item : all) {
            QuestionAttempt attempt = byQuestion.get(item.questionId());
            questionAccess.load(spaceId, item.questionId()).ifPresent(question ->
                    review.add(new ReviewItem(
                            item.position(),
                            question.questionId(),
                            question.type().name(),
                            question.difficulty(),
                            question.stem(),
                            attempt != null && attempt.isCorrect(),
                            attempt == null ? 0.0 : attempt.score().doubleValue(),
                            null,
                            question.explanation(),
                            attempt == null ? null : attempt.answer(),
                            // Everything is revealed now, including for questions left
                            // unanswered - the review is where the learning happens.
                            question.content().correctAnswer(),
                            item.timeSpentMs(),
                            item.selectionReason())));
        }

        return new SessionSummary(
                session.id(), session.mode().name(), session.status().name(),
                session.startedAt(), session.submittedAt(), session.activeMs(),
                session.totalItems(), session.answeredCount(), session.correctCount(),
                session.score() == null ? null : session.score().doubleValue(),
                session.scoreBreakdown(), review);
    }

    /** Flashcards carry an explicit recall grade; other formats derive one in Phase 6. */
    private Short gradeFrom(Answer answer) {
        return answer instanceof Answer.Grade grade ? (short) grade.value() : null;
    }

    private SessionMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return SessionMode.PRACTICE;
        }
        try {
            return SessionMode.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("session.mode_invalid",
                    "Unknown session mode \"" + raw + "\".");
        }
    }

    private Map<String, Object> configFrom(StartSessionRequest request) {
        Map<String, Object> config = new HashMap<>();
        if (request.length() != null) {
            config.put("length", request.length());
        }
        if (request.subjectIds() != null && !request.subjectIds().isEmpty()) {
            config.put("subjectIds", request.subjectIds().stream().map(UUID::toString).toList());
        }
        if (request.topicIds() != null && !request.topicIds().isEmpty()) {
            config.put("topicIds", request.topicIds().stream().map(UUID::toString).toList());
        }
        if (request.types() != null && !request.types().isEmpty()) {
            config.put("types", request.types());
        }
        if (request.difficulties() != null && !request.difficulties().isEmpty()) {
            config.put("difficulties", request.difficulties());
        }
        return config;
    }

    private SelectionFilters filtersFrom(StartSessionRequest request) {
        return new SelectionFilters(request.subjectIds(), request.topicIds(),
                request.types(), request.difficulties());
    }

    /**
     * The space's own config merged over its preparation type's blueprint.
     *
     * <p>Reuses the preparations module's merger rather than repeating the rules. Those rules
     * are not obvious - nested objects merge, lists replace - and a second implementation
     * would drift from the first and give a session different defaults from the one the
     * space screen displays.
     */
    private Map<String, Object> effectiveSettings(PreparationSpace space) {
        return blueprintMerger.merge(space.preparationType().blueprint(), space.config());
    }
}
