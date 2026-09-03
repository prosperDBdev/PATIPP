package com.patipp.questions.api;

import com.patipp.questions.domain.Question;
import com.patipp.questions.domain.QuestionRepository;
import com.patipp.questions.domain.QuestionStats;
import com.patipp.questions.domain.QuestionStatsRepository;
import com.patipp.questions.domain.QuestionStatus;
import com.patipp.questions.domain.QuestionType;
import com.patipp.questions.domain.QuestionVersion;
import com.patipp.questions.domain.content.QuestionContent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the questions module publishes to the rest of the application.
 *
 * <p>{@code sessions} needs to pick questions, serve them and grade the answers. It must not
 * need to know how questions are stored, versioned or validated to do that - so it gets this
 * narrow surface instead of the repositories. An ArchUnit rule keeps other modules out of
 * {@code questions.internal}; this is the sanctioned way through.
 *
 * <p>Selection here is random within the caller's filters. Phase 5 replaces the body of
 * {@link #selectForSession} with the adaptive engine, and no caller changes.
 */
@Component
public class QuestionAccess {

    private final QuestionRepository questions;
    private final QuestionStatsRepository stats;

    public QuestionAccess(QuestionRepository questions, QuestionStatsRepository stats) {
        this.questions = questions;
        this.stats = stats;
    }

    /**
     * Chooses questions for a session.
     *
     * @param filters optional narrowing by subject, topic, type and difficulty
     * @param limit   how many to serve; fewer are returned when the bank cannot supply them,
     *                because a short session is better than an error
     */
    @Transactional(readOnly = true)
    public List<SelectedQuestion> selectForSession(UUID spaceId, SelectionFilters filters, int limit) {
        List<Question> pool = questions.findAllLiveInSpace(spaceId).stream()
                .filter(question -> question.status() == QuestionStatus.ACTIVE)
                .filter(question -> question.currentVersion() != null)
                .filter(filters::matches)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));

        // Phase 3 selection is a shuffle. It is deliberately not "the first N", which would
        // serve the same questions every session and make practice useless after one run.
        Collections.shuffle(pool, new Random());

        List<SelectedQuestion> selected = new ArrayList<>();
        for (Question question : pool.subList(0, Math.min(limit, pool.size()))) {
            selected.add(new SelectedQuestion(
                    question.id(),
                    question.currentVersion().id(),
                    // Recorded from the start so "why this question?" is answerable. Phase 5
                    // replaces the contents; the field and the plumbing already exist.
                    Map.of("reason", "RANDOM", "phase", 3)));
        }
        return selected;
    }

    /** How many active questions match these filters, for telling the user before they start. */
    @Transactional(readOnly = true)
    public int countAvailable(UUID spaceId, SelectionFilters filters) {
        return (int) questions.findAllLiveInSpace(spaceId).stream()
                .filter(question -> question.status() == QuestionStatus.ACTIVE)
                .filter(question -> question.currentVersion() != null)
                .filter(filters::matches)
                .count();
    }

    /** Everything needed to render one question and grade its answer. */
    @Transactional(readOnly = true)
    public Optional<ServedQuestion> load(UUID spaceId, UUID questionId) {
        return questions.findInSpace(questionId, spaceId).map(question -> {
            QuestionVersion version = question.currentVersion();
            return new ServedQuestion(
                    question.id(),
                    version.id(),
                    question.type(),
                    question.difficulty().name(),
                    question.subjectId(),
                    question.topicId(),
                    question.estimatedSeconds(),
                    version.stem(),
                    version.explanation(),
                    version.hints(),
                    version.payload(),
                    QuestionContent.parse(question.type(), version.payload()));
        });
    }

    /**
     * Folds one answer into the question's aggregate statistics.
     *
     * <p>Item statistics, not learner statistics: how hard this question turned out to be for
     * everyone. The learner side lives in the attempt log and, from Phase 5, in mastery.
     */
    @Transactional
    public void recordAnswered(UUID questionId, boolean correct, Integer responseTimeMs) {
        stats.findById(questionId).ifPresent(row -> row.recordAnswer(correct, responseTimeMs));
    }

    /**
     * @param subjectIds  empty means any subject
     * @param topicIds    empty means any topic
     * @param types       empty means any format
     * @param difficulties empty means any level
     */
    public record SelectionFilters(
            List<UUID> subjectIds,
            List<UUID> topicIds,
            List<String> types,
            List<String> difficulties) {

        public SelectionFilters {
            subjectIds = subjectIds == null ? List.of() : List.copyOf(subjectIds);
            topicIds = topicIds == null ? List.of() : List.copyOf(topicIds);
            types = types == null ? List.of()
                    : types.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
            difficulties = difficulties == null ? List.of()
                    : difficulties.stream().map(value -> value.toUpperCase(Locale.ROOT)).toList();
        }

        public static SelectionFilters none() {
            return new SelectionFilters(List.of(), List.of(), List.of(), List.of());
        }

        boolean matches(Question question) {
            if (!subjectIds.isEmpty() && !subjectIds.contains(question.subjectId())) {
                return false;
            }
            if (!topicIds.isEmpty()
                    && (question.topicId() == null || !topicIds.contains(question.topicId()))) {
                return false;
            }
            if (!types.isEmpty() && !types.contains(question.type().name())) {
                return false;
            }
            return difficulties.isEmpty() || difficulties.contains(question.difficulty().name());
        }
    }

    public record SelectedQuestion(UUID questionId, UUID questionVersionId,
                                   Map<String, Object> selectionReason) {
    }

    /**
     * @param content the parsed, validated body - so the caller grades an answer without
     *                touching raw JSON
     */
    public record ServedQuestion(
            UUID questionId,
            UUID questionVersionId,
            QuestionType type,
            String difficulty,
            UUID subjectId,
            UUID topicId,
            int estimatedSeconds,
            String stem,
            String explanation,
            List<String> hints,
            Map<String, Object> payload,
            QuestionContent content) {
    }
}
