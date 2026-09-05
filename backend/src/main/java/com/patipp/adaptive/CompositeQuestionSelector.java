package com.patipp.adaptive;

import com.patipp.adaptive.LearnerModel.ItemState;
import com.patipp.adaptive.LearnerModel.TopicKey;
import com.patipp.adaptive.LearnerModel.TopicState;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Scores every candidate on five components, applies the guardrails, then samples.
 *
 * <p>Five components rather than one because no single signal is sufficient. Weakness alone
 * drills the same sore spot until you give up; difficulty fit alone ignores what you are
 * actually bad at; coverage alone is a syllabus checklist. Together they behave the way a
 * good tutor does - mostly your weak areas, at a level you can just about manage, without
 * forgetting the parts you have never touched.
 *
 * <p>And then it <em>samples</em> rather than taking the top N. Deterministic ranking gives
 * you the same session every morning, which is both boring and bad for recall.
 */
public final class CompositeQuestionSelector implements QuestionSelector {

    public static final String VERSION = "COMPOSITE_V1";

    // The weights. Retention leads because a review that slips is knowledge already paid for
    // and about to be lost, which is the most time-critical thing available.
    static final double W_DUE = 0.30;
    static final double W_WEAKNESS = 0.25;
    static final double W_FIT = 0.20;
    static final double W_COVERAGE = 0.15;
    static final double W_FRESHNESS = 0.10;

    /** No single topic may exceed this share of a session, unless a drill was asked for. */
    static final double MAX_TOPIC_SHARE = 0.40;

    /** At least one comfortable question in every window of this size. */
    static final int WIN_CADENCE = 6;
    static final double WIN_EXPECTATION = 0.85;

    /** Never this many EXPERT-band items in a row. */
    static final int MAX_HARD_RUN = 3;

    /** The smallest pool a random draw picks from, so a short session is still varied. */
    static final int MIN_SAMPLING_WINDOW = 8;

    /** How wide the difficulty sweet spot is, in Elo expectation units. */
    private static final double FIT_SIGMA = 0.15;

    /** A question seen this recently is nearly always the wrong thing to serve again. */
    private static final double FRESHNESS_HALF_LIFE_DAYS = 7.0;

    private final DifficultyTargeter targeter;

    public CompositeQuestionSelector() {
        this(new DifficultyTargeter());
    }

    public CompositeQuestionSelector(DifficultyTargeter targeter) {
        this.targeter = targeter;
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Selection select(LearnerModel model, List<Candidate> pool, Request request) {
        if (pool.isEmpty() || request.length() <= 0) {
            return new Selection(List.of(), Map.of("reason", "EMPTY_POOL"));
        }

        Instant now = model.asOf();
        boolean calibrating = model.isCalibrating();
        Random random = new Random(request.seed());

        // While the space is new, spread across the syllabus at authored difficulty instead
        // of chasing a weakness computed from four answers. The engine has nothing to adapt
        // to yet, and pretending otherwise is how it loses the learner's trust on day one.
        if (calibrating) {
            return calibrationDraw(model, pool, request, random);
        }

        Map<TopicKey, DifficultyTargeter.Target> targets = new HashMap<>();
        List<Scored> scored = new ArrayList<>(pool.size());

        for (Candidate candidate : pool) {
            DifficultyTargeter.Target target = targets.computeIfAbsent(candidate.topic(),
                    topic -> targeter.targetFor(model, topic, request.targetSuccess()));
            scored.add(score(model, candidate, target, now));
        }

        scored.sort(Comparator.comparingDouble(Scored::score).reversed());

        List<Chosen> chosen = sampleWithConstraints(scored, request, random);

        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("version", VERSION);
        diagnostics.put("poolSize", pool.size());
        diagnostics.put("calibrating", false);
        diagnostics.put("recovery", DifficultyTargeter.inRecovery(model.recent()));
        diagnostics.put("totalAttempts", model.totalAttempts());

        return new Selection(chosen, diagnostics);
    }

    /* ------------------------------------------------------------------ scoring */

    private Scored score(LearnerModel model, Candidate candidate,
                         DifficultyTargeter.Target target, Instant now) {
        ItemState item = model.item(candidate.questionId())
                .orElseGet(() -> ItemState.unseen(candidate.questionId()));
        TopicState topic = model.topic(candidate.topic()).orElse(null);

        double ability = model.abilityIn(candidate.topic());
        double expectation = Elo.expectation(ability, candidate.rating());

        double due = dueScore(item, now);
        double weakness = weaknessScore(topic);
        double fit = difficultyFit(expectation, target);
        double coverage = coverageGap(topic);
        double freshness = freshness(item, now);

        double total = W_DUE * due
                + W_WEAKNESS * weakness
                + W_FIT * fit
                + W_COVERAGE * coverage
                + W_FRESHNESS * freshness;

        // Seen today already: almost certainly not the best use of the next slot.
        if (item.lastSeenAt() != null && Duration.between(item.lastSeenAt(), now).toHours() < 2) {
            total -= 0.30;
        }

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("due", round(due));
        components.put("weakness", round(weakness));
        components.put("difficultyFit", round(fit));
        components.put("coverageGap", round(coverage));
        components.put("freshness", round(freshness));
        components.put("expectation", round(expectation));
        components.put("targetRating", Math.round(target.rating()));
        components.put("itemRating", Math.round(candidate.rating()));

        String reason = dominantReason(target, due, weakness, fit, coverage, item);

        return new Scored(candidate, Math.max(0.0, total), expectation, reason,
                explain(reason, topic, target, expectation), components);
    }

    /**
     * Retention debt.
     *
     * <p>Inert until Phase 6 gives items a review schedule: with no {@code dueAt} every
     * candidate reads as new and scores the same, so this weight contributes nothing to the
     * ordering rather than distorting it. Phase 6 turns it on without rebalancing anything.
     */
    private static double dueScore(ItemState item, Instant now) {
        if (item.dueAt() == null) {
            return item.isNew() ? 0.45 : 0.05;
        }
        if (item.dueAt().isAfter(now)) {
            return 0.05;
        }
        double overdueDays = Duration.between(item.dueAt(), now).toMillis() / 86_400_000.0;
        double stability = Math.max(1.0, item.stability());
        return Math.clamp(overdueDays / stability, 0.0, 1.0);
    }

    /** This is what makes a topic at 49% outrank one at 92%. */
    private static double weaknessScore(TopicState topic) {
        if (topic == null || !topic.isAssessed()) {
            // Not "very weak" and not "fine" - unmeasured. Coverage is the component that
            // should be pulling these in, not weakness.
            return 0.5;
        }
        double base = 1.0 - Math.clamp(topic.decayedAccuracy(), 0.0, 1.0);
        return Math.clamp(base + (topic.consecutiveWrong() >= 2 ? 0.2 : 0.0), 0.0, 1.0);
    }

    /**
     * A Gaussian around the target: questions near the sweet spot score highest, and both
     * "far too easy" and "far too hard" fall away smoothly rather than at a threshold.
     */
    private static double difficultyFit(double expectation, DifficultyTargeter.Target target) {
        double delta = expectation - target.successRate();
        return Math.exp(-(delta * delta) / (2 * FIT_SIGMA * FIT_SIGMA));
    }

    /** Stops the engine drilling three known topics while five sit untouched. */
    private static double coverageGap(TopicState topic) {
        if (topic == null) {
            return 1.0;
        }
        double gap = 1.0 - Math.clamp(topic.coverage(), 0.0, 1.0);
        double weight = topic.weight() <= 0 ? 1.0 : topic.weight();
        return Math.clamp(gap * Math.min(2.0, weight), 0.0, 1.0);
    }

    /** Anti-repetition: a question answered yesterday should rarely come back today. */
    private static double freshness(ItemState item, Instant now) {
        if (item.lastSeenAt() == null) {
            return 1.0;
        }
        double days = Duration.between(item.lastSeenAt(), now).toMillis() / 86_400_000.0;
        return 1.0 - Math.pow(0.5, Math.max(0.0, days) / FRESHNESS_HALF_LIFE_DAYS);
    }

    /* ------------------------------------------------------------------ constraints */

    /**
     * Samples from the strongest candidates, refusing draws that would break a guardrail.
     *
     * <p>The constraints are applied here rather than by filtering beforehand because they
     * are about the shape of the <em>session</em>, not about any one question: whether a
     * third EXPERT item in a row is acceptable depends entirely on the two before it.
     */
    private List<Chosen> sampleWithConstraints(List<Scored> scored, Request request,
                                               Random random) {
        int length = Math.min(request.length(), scored.size());
        int maxPerTopic = request.singleTopicDrill()
                ? length
                : Math.max(1, (int) Math.ceil(length * MAX_TOPIC_SHARE));

        // Everything still available, best first. The sampling window is applied inside each
        // draw, against the candidates that are actually eligible at that moment - narrowing
        // the list up front instead would let the window fill with questions that are already
        // at their topic's cap, and the draw would then find nothing and relax a constraint it
        // did not need to.
        List<Scored> remaining = new ArrayList<>(scored);

        List<Chosen> chosen = new ArrayList<>(length);
        Map<TopicKey, Integer> perTopic = new HashMap<>();
        int hardRun = 0;
        int sinceWin = 0;

        while (chosen.size() < length && !remaining.isEmpty()) {
            // Wide enough for variety, narrow enough that a weak question never turns up
            // simply because it got lucky in the draw.
            int window = Math.max(MIN_SAMPLING_WINDOW, 3 * (length - chosen.size()));
            boolean needWin = sinceWin >= WIN_CADENCE - 1;
            boolean noMoreHard = hardRun >= MAX_HARD_RUN - 1;

            java.util.function.Predicate<Scored> spreadsTopics = candidate ->
                    perTopic.getOrDefault(candidate.candidate().topic(), 0) < maxPerTopic;
            java.util.function.Predicate<Scored> notAnotherHardOne = candidate ->
                    !noMoreHard || candidate.expectation() >= 0.4;
            java.util.function.Predicate<Scored> isAWin = candidate ->
                    !needWin || candidate.expectation() >= WIN_EXPECTATION;

            // Relaxed one at a time, weakest guarantee first. Dropping them all together
            // would mean an unsatisfiable win cadence - a bank with no easy questions in it -
            // silently also switching off diversity, and handing the learner ten questions on
            // their worst topic. A constraint that cannot be met must cost only itself.
            Scored pick = firstNonNull(
                    () -> draw(remaining, window, request.temperature(), random,
                            spreadsTopics.and(notAnotherHardOne).and(isAWin)),
                    () -> draw(remaining, window, request.temperature(), random,
                            spreadsTopics.and(notAnotherHardOne)),
                    () -> draw(remaining, window, request.temperature(), random, spreadsTopics),
                    // Diversity goes last: at this point the pool genuinely cannot spread any
                    // further, and a short session would be worse than a narrow one.
                    () -> draw(remaining, window, request.temperature(), random,
                            candidate -> true));

            if (pick == null) {
                break;
            }

            remaining.remove(pick);
            perTopic.merge(pick.candidate().topic(), 1, Integer::sum);
            hardRun = pick.expectation() < 0.4 ? hardRun + 1 : 0;
            sinceWin = pick.expectation() >= WIN_EXPECTATION ? 0 : sinceWin + 1;

            chosen.add(pick.toChosen());
        }

        return List.copyOf(chosen);
    }

    /** Tries each draw in turn and returns the first that finds anything. */
    @SafeVarargs
    private static Scored firstNonNull(java.util.function.Supplier<Scored>... attempts) {
        for (java.util.function.Supplier<Scored> attempt : attempts) {
            Scored pick = attempt.get();
            if (pick != null) {
                return pick;
            }
        }
        return null;
    }

    /**
     * Picks one candidate with probability proportional to {@code exp(score / T)}.
     *
     * <p>At {@code T = 0} this is a deterministic argmax, which is what the tests use. Above
     * it, strong candidates still dominate but the session is not identical every morning.
     */
    private static Scored draw(List<Scored> remaining, int window, double temperature,
                               Random random, java.util.function.Predicate<Scored> allowed) {
        // Filter first, then take the window. The other order would fill the window with
        // candidates that are already ruled out and report that nothing qualifies.
        List<Scored> eligible = remaining.stream().filter(allowed).limit(window).toList();
        if (eligible.isEmpty()) {
            return null;
        }
        if (temperature <= 0) {
            return eligible.get(0);
        }

        double[] weights = new double[eligible.size()];
        double total = 0;
        // Relative to the best score, so exp() cannot overflow and the distribution does not
        // depend on the absolute scale of the scores.
        double best = eligible.get(0).score();
        for (int i = 0; i < eligible.size(); i++) {
            weights[i] = Math.exp((eligible.get(i).score() - best) / temperature);
            total += weights[i];
        }

        double target = random.nextDouble() * total;
        double running = 0;
        for (int i = 0; i < eligible.size(); i++) {
            running += weights[i];
            if (running >= target) {
                return eligible.get(i);
            }
        }
        return eligible.get(eligible.size() - 1);
    }

    /* ------------------------------------------------------------------ cold start */

    /**
     * The first session in a space: spread widely, at the difficulty the author intended.
     *
     * <p>Deliberately not adaptive. There is nothing yet to adapt to, and the point of these
     * twelve or so questions is to produce an ability estimate per topic that everything
     * afterwards can build on.
     */
    private Selection calibrationDraw(LearnerModel model, List<Candidate> pool, Request request,
                                      Random random) {
        Map<TopicKey, List<Candidate>> byTopic = new LinkedHashMap<>();
        for (Candidate candidate : pool) {
            byTopic.computeIfAbsent(candidate.topic(), key -> new ArrayList<>()).add(candidate);
        }
        // Prefer questions near the middle of the ladder: an EASY question tells you almost
        // nothing about a strong learner, and an EXPERT one nothing about a weak one.
        byTopic.values().forEach(list -> {
            java.util.Collections.shuffle(list, random);
            list.sort(Comparator.comparingDouble(
                    candidate -> Math.abs(candidate.rating() - Elo.STARTING_RATING)));
        });

        List<Chosen> chosen = new ArrayList<>();
        boolean tookOne = true;
        // Round-robin across topics, so twelve questions cover twelve areas rather than
        // twelve corners of the same one.
        while (chosen.size() < request.length() && tookOne) {
            tookOne = false;
            for (List<Candidate> candidates : byTopic.values()) {
                if (chosen.size() >= request.length() || candidates.isEmpty()) {
                    continue;
                }
                Candidate candidate = candidates.remove(0);
                double expectation = Elo.expectation(
                        model.abilityIn(candidate.topic()), candidate.rating());
                chosen.add(new Chosen(
                        candidate.questionId(),
                        candidate.questionVersionId(),
                        0.5,
                        "CALIBRATION",
                        "Getting a first read on what you already know",
                        expectation,
                        Map.of("expectation", round(expectation),
                                "itemRating", Math.round(candidate.rating()))));
                tookOne = true;
            }
        }

        return new Selection(List.copyOf(chosen), Map.of(
                "version", VERSION,
                "poolSize", pool.size(),
                "calibrating", true,
                "attemptsUntilAdaptive",
                Math.max(0, LearnerModel.CALIBRATION_ATTEMPTS - model.totalAttempts())));
    }

    /* ------------------------------------------------------------------ explanation */

    /** Whichever weighted component contributed most, so the reason is the true one. */
    private static String dominantReason(DifficultyTargeter.Target target, double due,
                                         double weakness, double fit, double coverage,
                                         ItemState item) {
        if (target.recovery()) {
            return "RECOVERY";
        }
        if (item.dueAt() != null && W_DUE * due >= W_WEAKNESS * weakness) {
            return "DUE_REVIEW";
        }

        double weighted = W_WEAKNESS * weakness;
        String reason = "WEAK_TOPIC";
        if (W_COVERAGE * coverage > weighted) {
            weighted = W_COVERAGE * coverage;
            reason = "COVERAGE_GAP";
        }
        if (W_FIT * fit > weighted) {
            reason = "DIFFICULTY_FIT";
        }
        return reason;
    }

    private static String explain(String reason, TopicState topic,
                                  DifficultyTargeter.Target target, double expectation) {
        return switch (reason) {
            case "RECOVERY" -> target.explanation();
            case "DUE_REVIEW" -> "This one is due for review";
            case "WEAK_TOPIC" -> topic == null
                    ? "Working on an area you have not measured yet"
                    : "You are at %d%% here recently".formatted(
                            Math.round(topic.decayedAccuracy() * 100));
            case "COVERAGE_GAP" -> topic == null || topic.coverage() <= 0
                    ? "You have not tried anything from this area yet"
                    : "You have only seen %d%% of this area".formatted(
                            Math.round(topic.coverage() * 100));
            default -> "About right for you — around a %d%% chance".formatted(
                    Math.round(expectation * 100));
        };
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    /** A candidate with its score, kept out of the public API because it is scratch space. */
    private record Scored(Candidate candidate, double score, double expectation, String reason,
                          String explanation, Map<String, Object> components) {

        Chosen toChosen() {
            return new Chosen(candidate.questionId(), candidate.questionVersionId(),
                    score, reason, explanation, expectation, components);
        }
    }
}
