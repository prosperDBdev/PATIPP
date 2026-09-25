package com.patipp.sessions.internal;

import com.patipp.adaptive.LearnerModel;
import com.patipp.adaptive.LearnerModel.TopicKey;
import com.patipp.adaptive.QuestionSelector;
import com.patipp.adaptive.QuestionSelector.Candidate;
import com.patipp.adaptive.QuestionSelector.Chosen;
import com.patipp.adaptive.QuestionSelector.Selection;
import com.patipp.learning.api.LearningAccess;
import com.patipp.questions.api.QuestionAccess;
import com.patipp.questions.api.QuestionAccess.SelectionFilters;
import com.patipp.sessions.api.SessionDtos.StartSessionRequest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs the adaptive engine on behalf of the session engine.
 *
 * <p>Three modules meet here and nowhere else: {@code questions} supplies a pool of content,
 * {@code learning} supplies what is known about the learner, and {@code adaptive} decides.
 * Doing this inside {@code questions} instead - which an earlier note in {@code QuestionAccess}
 * proposed - would have made the content module depend on per-learner state, and the first
 * rule of this codebase is that user performance never attaches to a question.
 *
 * <p>The output is deliberately the same {@code SelectedQuestion} list the random and weighted
 * paths produce, so everything downstream is unchanged.
 */
@Component
public class AdaptiveSelection {

    private static final Logger log = LoggerFactory.getLogger(AdaptiveSelection.class);

    /**
     * How far ahead a review session will reach for something that is nearly due.
     *
     * <p>A card you have just failed is rescheduled ten minutes out by the first learning step,
     * and it is the single most valuable thing you could see again. Refusing to serve it because
     * it is due in nine minutes rather than now would be the letter of the schedule defeating
     * its purpose — and it is why "Again brings the card back in the same sitting" is true rather
     * than aspirational.
     *
     * <p>Fifteen minutes, not hours: wide enough to cover the learning steps, narrow enough that
     * nothing genuinely spaced is pulled forward.
     */
    private static final Duration WITHIN_THIS_SITTING = Duration.ofMinutes(15);

    private final QuestionAccess questions;
    private final LearningAccess learning;
    private final QuestionSelector selector;

    public AdaptiveSelection(QuestionAccess questions, LearningAccess learning,
                             QuestionSelector selector) {
        this.questions = questions;
        this.learning = learning;
        this.selector = selector;
    }

    public List<QuestionAccess.SelectedQuestion> select(UUID userId, UUID spaceId,
                                                        SelectionFilters filters, int length,
                                                        long seed,
                                                        Map<String, Object> effectiveSettings,
                                                        StartSessionRequest request) {
        List<QuestionAccess.Candidate> pool = questions.candidatesFor(spaceId, filters);
        if (pool.isEmpty()) {
            return List.of();
        }

        LearnerModel model = learning.modelFor(userId, spaceId);

        List<Candidate> candidates = new ArrayList<>(pool.size());
        for (QuestionAccess.Candidate candidate : pool) {
            candidates.add(new Candidate(
                    candidate.questionId(),
                    candidate.questionVersionId(),
                    TopicKey.of(candidate.subjectId(), candidate.topicId()),
                    candidate.rating(),
                    candidate.authoredDifficulty(),
                    candidate.estimatedSeconds()));
        }

        Selection selection = selector.select(model, candidates, new QuestionSelector.Request(
                length,
                seed,
                targetSuccessRate(effectiveSettings),
                QuestionSelector.Request.DEFAULT_TEMPERATURE,
                // Asking for one topic is an explicit instruction, and refusing it in the
                // name of diversity would be the engine overruling the person using it.
                isSingleTopicDrill(request)));

        log.debug("Adaptive selection for {} in {}: {} of {} candidates, {}",
                userId, spaceId, selection.items().size(), candidates.size(),
                selection.diagnostics());

        List<QuestionAccess.SelectedQuestion> selected = new ArrayList<>(selection.items().size());
        for (Chosen chosen : selection.items()) {
            selected.add(new QuestionAccess.SelectedQuestion(
                    chosen.questionId(), chosen.questionVersionId(),
                    reasonFor(chosen, selection)));
        }
        return selected;
    }

    /**
     * What is due, most overdue first, topped up with new material.
     *
     * <p>Review debt comes first because it is knowledge already paid for and about to be lost,
     * which is the most time-critical thing available. But a review session with nothing due
     * would be an empty screen and a wasted intention, so it falls through to unseen items — at
     * which point it is doing the useful thing of building the backlog rather than clearing it.
     */
    public List<QuestionAccess.SelectedQuestion> selectDueFirst(UUID userId, UUID spaceId,
                                                                SelectionFilters filters,
                                                                int length, long seed) {
        List<QuestionAccess.Candidate> pool = questions.candidatesFor(spaceId, filters);
        if (pool.isEmpty()) {
            return List.of();
        }

        Map<UUID, Instant> dueDates = learning.dueDatesFor(userId, spaceId);
        Instant now = learning.now();
        Instant horizon = now.plus(WITHIN_THIS_SITTING);

        List<QuestionAccess.Candidate> due = new ArrayList<>();
        List<QuestionAccess.Candidate> unseen = new ArrayList<>();

        for (QuestionAccess.Candidate candidate : pool) {
            Instant dueAt = dueDates.get(candidate.questionId());
            if (dueAt == null) {
                unseen.add(candidate);
            } else if (!dueAt.isAfter(horizon)) {
                due.add(candidate);
            }
            // Anything due beyond the horizon is deliberately skipped: showing an item early is
            // the one thing a spaced-repetition system exists to avoid.
        }

        due.sort(Comparator.comparing(candidate -> dueDates.get(candidate.questionId())));
        // Shuffled, so a review session that falls through to new material is not simply the
        // question bank in creation order every time.
        Collections.shuffle(unseen, new Random(seed));

        List<QuestionAccess.SelectedQuestion> selected = new ArrayList<>(length);
        for (QuestionAccess.Candidate candidate : due) {
            if (selected.size() >= length) {
                break;
            }
            double overdueDays = Duration.between(dueDates.get(candidate.questionId()), now)
                    .toMillis() / 86_400_000.0;
            selected.add(new QuestionAccess.SelectedQuestion(
                    candidate.questionId(), candidate.questionVersionId(),
                    Map.of("reason", "DUE_REVIEW",
                            "why", overdueDays >= 1
                                    ? "Due %d day%s ago".formatted(Math.round(overdueDays),
                                            Math.round(overdueDays) == 1 ? "" : "s")
                                    : overdueDays < 0
                                            ? "You just missed this one"
                                            : "Due for review now",
                            "overdueDays", Math.round(overdueDays * 100) / 100.0,
                            "engine", "FSRS_V1")));
        }

        for (QuestionAccess.Candidate candidate : unseen) {
            if (selected.size() >= length) {
                break;
            }
            selected.add(new QuestionAccess.SelectedQuestion(
                    candidate.questionId(), candidate.questionVersionId(),
                    Map.of("reason", "NEW_MATERIAL",
                            "why", "Nothing is due, so this is something you have not seen",
                            "engine", "FSRS_V1")));
        }

        log.debug("Due-first selection for {} in {}: {} due, {} new, {} chosen",
                userId, spaceId, due.size(), unseen.size(), selected.size());

        return selected;
    }

    /**
     * The reason recorded against the served item.
     *
     * <p>Written from the first session rather than added later, because "why this question?"
     * has to be answerable retrospectively and a reason reconstructed after the fact is a
     * guess. Nothing here gives an answer away.
     */
    private static Map<String, Object> reasonFor(Chosen chosen, Selection selection) {
        Map<String, Object> reason = new LinkedHashMap<>();
        reason.put("reason", chosen.reason());
        reason.put("why", chosen.explanation());
        reason.put("score", Math.round(chosen.score() * 1000) / 1000.0);
        reason.put("expectation", Math.round(chosen.expectation() * 1000) / 1000.0);
        reason.put("components", chosen.components());
        reason.put("engine", selection.diagnostics().get("version"));
        return reason;
    }

    /** A space may set its own target, so an onboarding mode can be gentler than 78%. */
    private static Double targetSuccessRate(Map<String, Object> effectiveSettings) {
        Object value = effectiveSettings == null ? null
                : effectiveSettings.get("targetSuccessRate");
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private static boolean isSingleTopicDrill(StartSessionRequest request) {
        return (request.topicIds() != null && request.topicIds().size() == 1)
                || (request.subjectIds() != null && request.subjectIds().size() == 1
                        && (request.topicIds() == null || request.topicIds().isEmpty()));
    }
}
