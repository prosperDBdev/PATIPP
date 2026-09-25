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
                Map.of("reason", "RANDOM",
                        "why", "Drawn at random from what matches your filters",
                        "seed", seed));
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

        return toSelection(chosen, Map.of("reason", "BLUEPRINT_WEIGHTED",
                "why", "Sampled to match how the real paper is weighted",
                "seed", seed));
    }

    /**
     * The eligible questions, in a stable order.
     *
     * <p>The order matters and is not incidental. Every seeded draw shuffles this list, so two
     * draws with the same seed only agree if the list arrives the same way both times.
     * {@code findAllLiveInSpace} therefore orders by {@code (createdAt, id)} rather than by
     * timestamp alone: questions written in a loop can share a timestamp to the microsecond,
     * and the ambiguity showed up as a mock exam that was occasionally not reproducible from
     * its own recorded seed.
     */
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

    /**
     * Everything that matches the filters, with each question's measured difficulty.
     *
     * <p>The pool the adaptive engine scores. This module decides what is <em>eligible</em> -
     * active, current, matching the filters - and stops there. Which of them a particular
     * learner should see is not a question about content, and answering it here would put
     * per-learner state inside the content module, which is the one thing the data model
     * forbids outright.
     */
    @Transactional(readOnly = true)
    public List<Candidate> candidatesFor(UUID spaceId, SelectionFilters filters) {
        List<Question> pool = candidatePool(spaceId, filters);

        Map<UUID, QuestionStats> ratings = new java.util.HashMap<>();
        stats.findAllById(pool.stream().map(Question::id).toList())
                .forEach(row -> ratings.put(row.questionId(), row));

        List<Candidate> candidates = new ArrayList<>(pool.size());
        for (Question question : pool) {
            QuestionStats row = ratings.get(question.id());
            candidates.add(new Candidate(
                    question.id(),
                    question.currentVersion().id(),
                    question.subjectId(),
                    question.topicId(),
                    // Falls back to the authored prior if statistics are somehow missing, so
                    // a question is never silently dropped from selection.
                    row == null ? question.difficulty().seedRating() : row.eloRating().doubleValue(),
                    question.difficulty().name(),
                    question.estimatedSeconds()));
        }
        return candidates;
    }

    /**
     * @param rating the measured difficulty, which diverges from {@code authoredDifficulty}
     *               as real answers accumulate
     */
    public record Candidate(
            UUID questionId,
            UUID questionVersionId,
            UUID subjectId,
            UUID topicId,
            double rating,
            String authoredDifficulty,
            int estimatedSeconds) {
    }

    /**
     * How many active questions each subject and topic holds.
     *
     * <p>The denominator of coverage: "you have seen 6 of the 40 questions in this topic".
     * Returned as counts rather than as fractions because the module that asks holds the
     * numerator, and because this is a fact about the bank that changes when questions are
     * added - a stored fraction would go quietly stale.
     *
     * <p>Deliberately not typed in the engine's vocabulary. {@code questions} owns content and
     * must not learn what a learner model is.
     */
    @Transactional(readOnly = true)
    public List<TopicCount> activeCountsByTopic(UUID spaceId) {
        Map<List<UUID>, Integer> counts = new LinkedHashMap<>();

        for (Question question : questions.findAllLiveInSpace(spaceId)) {
            if (question.status() != QuestionStatus.ACTIVE || question.currentVersion() == null) {
                continue;
            }
            // A list of two, one of which may be null, as the key: Map.of rejects nulls and
            // untagged questions are a real bucket that has to be counted.
            counts.merge(java.util.Arrays.asList(question.subjectId(), question.topicId()),
                    1, Integer::sum);
        }

        return counts.entrySet().stream()
                .map(entry -> new TopicCount(
                        entry.getKey().get(0), entry.getKey().get(1), entry.getValue()))
                .toList();
    }

    /** @param topicId null for the subject's questions that have no topic */
    public record TopicCount(UUID subjectId, UUID topicId, int activeQuestions) {
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
     * The question's measured difficulty.
     *
     * <p>Seeded from the authored label and corrected by real responses, so this diverges from
     * {@code question.difficulty()} over time - and when it does, this is the one that is
     * right. The label was one person's guess; this is what actually happened.
     */
    @Transactional(readOnly = true)
    public Optional<ItemRating> ratingOf(UUID questionId) {
        return stats.findById(questionId)
                .map(row -> new ItemRating(row.eloRating().doubleValue(), row.ratingCount()));
    }

    /**
     * Puts every question in a space back to the prior its authored label implies.
     *
     * <p>Only for a rebuild from the attempt log, which must not start from ratings that
     * already contain the history it is about to replay.
     */
    @Transactional
    public void resetRatings(UUID spaceId) {
        for (Question question : questions.findAllLiveInSpace(spaceId)) {
            stats.findById(question.id())
                    .ifPresent(row -> row.resetRating(question.difficulty()));
        }
    }

    /** Stores a new measured difficulty. The learner side of the same update lives elsewhere. */
    @Transactional
    public void applyRating(UUID questionId, double newRating) {
        stats.findById(questionId).ifPresent(row -> row.applyRating(newRating));
    }

    /** @param ratingCount how much evidence is behind it, which decides how far it may move */
    public record ItemRating(double rating, int ratingCount) {
    }

    /**
     * How long this question actually takes people.
     *
     * <p>Used to tell "I knew it" from "I worked it out": the author's estimate is a guess, and
     * a reliably optimistic one, so the observed mean replaces it once there are enough samples
     * to be a mean rather than an anecdote.
     */
    @Transactional(readOnly = true)
    public Optional<ItemTiming> timingOf(UUID questionId) {
        return stats.findById(questionId)
                .map(row -> new ItemTiming(row.avgResponseMs(), row.timesServed()));
    }

    /** @param sampleCount how many answers the mean is built from */
    public record ItemTiming(Integer averageResponseMs, int sampleCount) {
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
