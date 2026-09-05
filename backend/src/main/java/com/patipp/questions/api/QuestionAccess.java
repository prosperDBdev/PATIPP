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
import java.util.LinkedHashMap;
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
     * Chooses questions for a session, drawing evenly from whatever matches the filters.
     *
     * @param filters optional narrowing by subject, topic, type and difficulty
     * @param limit   how many to serve; fewer are returned when the bank cannot supply them,
     *                because a short session is better than an error
     * @param seed    makes the draw reproducible, so an exam can be re-created exactly
     */
    @Transactional(readOnly = true)
    public List<SelectedQuestion> selectForSession(UUID spaceId, SelectionFilters filters,
                                                   int limit, long seed) {
        List<Question> pool = candidatePool(spaceId, filters);

        // Deliberately not "the first N", which would serve the same questions every time and
        // make a second session pointless.
        Collections.shuffle(pool, new Random(seed));

        return toSelection(pool.subList(0, Math.min(limit, pool.size())),
                Map.of("reason", "RANDOM", "seed", seed));
    }

    /**
     * Chooses questions in proportion to each subject's blueprint weighting.
     *
     * <p>If React is 30% of the paper, roughly 30% of the questions come from React. Quotas
     * are allocated by weight, then any shortfall - a subject that simply has too few
     * questions - is topped up from everything else rather than returning a short exam. A mock
     * that silently drops to 12 questions because one subject is thin would misreport your
     * readiness, which is the one thing it exists to measure.
     *
     * @param subjectWeights subject id to weight; an empty map falls back to an even draw
     */
    @Transactional(readOnly = true)
    public List<SelectedQuestion> selectWeighted(UUID spaceId, SelectionFilters filters,
                                                 int limit, long seed,
                                                 Map<UUID, Double> subjectWeights) {
        if (subjectWeights == null || subjectWeights.isEmpty()) {
            return selectForSession(spaceId, filters, limit, seed);
        }

        List<Question> pool = candidatePool(spaceId, filters);
        Random random = new Random(seed);

        Map<UUID, List<Question>> bySubject = new LinkedHashMap<>();
        for (Question question : pool) {
            bySubject.computeIfAbsent(question.subjectId(), key -> new ArrayList<>()).add(question);
        }
        bySubject.values().forEach(list -> Collections.shuffle(list, random));

        // Only subjects that actually have questions get a share of the weight; otherwise a
        // heavily weighted but empty subject would swallow quota and shorten the exam.
        double totalWeight = bySubject.keySet().stream()
                .mapToDouble(id -> Math.max(0.0, subjectWeights.getOrDefault(id, 1.0)))
                .sum();

        List<Question> chosen = new ArrayList<>();
        List<Question> leftovers = new ArrayList<>();

        if (totalWeight > 0) {
            for (Map.Entry<UUID, List<Question>> entry : bySubject.entrySet()) {
                double weight = Math.max(0.0, subjectWeights.getOrDefault(entry.getKey(), 1.0));
                int quota = (int) Math.round(limit * (weight / totalWeight));
                List<Question> available = entry.getValue();

                int take = Math.min(quota, available.size());
                chosen.addAll(available.subList(0, take));
                leftovers.addAll(available.subList(take, available.size()));
            }
        } else {
            leftovers.addAll(pool);
        }

        // Rounding and thin subjects both leave gaps; fill them so the exam is the length
        // that was asked for whenever the bank can supply it.
        Collections.shuffle(leftovers, random);
        for (Question question : leftovers) {
            if (chosen.size() >= limit) {
                break;
            }
            chosen.add(question);
        }

        if (chosen.size() > limit) {
            chosen = new ArrayList<>(chosen.subList(0, limit));
        }
        // Interleave, so the paper does not run subject by subject in blocks.
        Collections.shuffle(chosen, random);

        return toSelection(chosen, Map.of("reason", "BLUEPRINT_WEIGHTED", "seed", seed));
    }

    private List<Question> candidatePool(UUID spaceId, SelectionFilters filters) {
        return questions.findAllLiveInSpace(spaceId).stream()
                .filter(question -> question.status() == QuestionStatus.ACTIVE)
                .filter(question -> question.currentVersion() != null)
                .filter(filters::matches)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private List<SelectedQuestion> toSelection(List<Question> chosen, Map<String, Object> reason) {
        List<SelectedQuestion> selected = new ArrayList<>();
        for (Question question : chosen) {
            // The reason is recorded from the start so "why this question?" stays answerable.
            // Phase 5 fills it with real reasoning; the field and the plumbing already exist.
            selected.add(new SelectedQuestion(
                    question.id(), question.currentVersion().id(), reason));
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
