package com.patipp.analytics.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.patipp.analytics.model.ReadinessInputs.ComponentWeights;
import com.patipp.analytics.model.ReadinessInputs.SubjectEvidence;
import com.patipp.analytics.model.ReadinessResult.ConfidenceBand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The readiness model, against learners built by hand.
 *
 * <p>No Spring, no database. The point of that is not speed for its own sake: the only honest way
 * to judge a change to six weights is to see what it does to many different learners at once, and
 * that is only affordable if each one costs microseconds.
 */
class WeightedReadinessModelTest {

    private static final Instant NOW = Instant.parse("2026-06-01T09:00:00Z");

    private final WeightedReadinessModel model = new WeightedReadinessModel();

    /** A learner with nothing recorded at all. */
    private ReadinessInputs empty() {
        return new ReadinessInputs(List.of(), 0, NOW, null, 0, 0, 0, 0, 10,
                List.of(), List.of(), null);
    }

    /** A subject with everything going well. */
    private SubjectEvidence strong(String name) {
        return new SubjectEvidence(name, 1.0, 5, 5, 0.92, 2.5, 80, 38, 40);
    }

    private SubjectEvidence weak(String name) {
        return new SubjectEvidence(name, 1.0, 5, 5, 0.45, 2.0, 60, 30, 40);
    }

    /** A well-prepared learner: broad coverage, good accuracy, reviews clear, mocks sat. */
    private ReadinessInputs prepared() {
        return new ReadinessInputs(
                List.of(strong("JavaScript"), strong("React")),
                400, NOW, null,
                0, 0, 120,
                12, 10,
                List.of(84.0, 79.0), List.of(3, 20),
                0.80);
    }

    @Nested
    @DisplayName("the score as a whole")
    class Overall {

        @Test
        @DisplayName("a well-prepared learner scores well")
        void preparedLearnerScoresWell() {
            ReadinessResult result = model.compute(prepared(), ComponentWeights.standard(), null);

            assertThat(result.score()).isBetween(70.0, 100.0);
            assertThat(result.band()).isEqualTo(ConfidenceBand.HIGH);
            assertThat(result.version()).isEqualTo(WeightedReadinessModel.VERSION);
        }

        @Test
        @DisplayName("a learner with nothing recorded scores zero, not a flattering default")
        void emptyLearnerScoresZero() {
            ReadinessResult result = model.compute(empty(), ComponentWeights.standard(), null);

            assertThat(result.score()).isZero();
            assertThat(result.band()).isEqualTo(ConfidenceBand.CALIBRATING);
            assertThat(result.headline()).contains("calibrating");
        }

        @Test
        @DisplayName("it is not the average quiz percentage")
        void notJustAccuracy() {
            // 95% accurate, but only on easy questions in one of five topics, nothing reviewed,
            // no mock sat, and studied on two days out of fourteen. A quiz average would call
            // this person ready. They are not.
            ReadinessInputs narrow = new ReadinessInputs(
                    List.of(new SubjectEvidence("JavaScript", 1.0, 5, 1, 0.95, 1.0, 40, 5, 60)),
                    40, NOW, null, 0, 0, 0, 2, 10, List.of(), List.of(), 0.30);

            ReadinessResult result = model.compute(narrow, ComponentWeights.standard(), null);

            assertThat(result.components().get("accuracy")).isGreaterThan(50.0);
            // The whole justification for six components rather than one.
            assertThat(result.score()).isLessThan(40.0);
        }

        @Test
        @DisplayName("blueprint weights are normalised, so a bad blueprint cannot exceed 100")
        void weightsAreNormalised() {
            // Weights summing to 3.0. Trusted as given, this would report 300% ready.
            ComponentWeights sloppy = new ComponentWeights(Map.of(
                    "coverage", 0.5, "accuracy", 0.5, "depth", 0.5,
                    "retention", 0.5, "consistency", 0.5, "mock", 0.5));

            ReadinessResult result = model.compute(prepared(), sloppy, null);

            assertThat(result.raw()).isLessThanOrEqualTo(100.0);
            assertThat(result.weights().values().stream().mapToDouble(Double::doubleValue).sum())
                    .isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        }

        @Test
        @DisplayName("a space can weight the components differently")
        void blueprintCanReweight() {
            // An interview space caring about depth far more than coverage.
            ComponentWeights depthFirst = new ComponentWeights(Map.of(
                    "coverage", 0.05, "accuracy", 0.20, "depth", 0.50,
                    "retention", 0.10, "consistency", 0.05, "mock", 0.10));

            ReadinessInputs shallow = new ReadinessInputs(
                    List.of(strong("JavaScript")), 300, NOW, null,
                    0, 0, 100, 12, 10, List.of(80.0), List.of(2), 0.30);

            assertThat(model.compute(shallow, depthFirst, null).score())
                    .isLessThan(model.compute(shallow, ComponentWeights.standard(), null).score());
        }
    }

    @Nested
    @DisplayName("the confidence factor")
    class Confidence {

        @Test
        @DisplayName("twelve answers cannot produce a high readiness")
        void earlyScoresAreCapped() {
            // Everything perfect, on twelve answers. A system that reports 88% here is lying at
            // the exact moment the stakes are highest.
            ReadinessInputs thin = new ReadinessInputs(
                    List.of(new SubjectEvidence("JavaScript", 1.0, 1, 1, 1.0, 4.0, 12, 12, 12)),
                    12, NOW, null, 0, 0, 12, 14, 10,
                    List.of(100.0), List.of(0), 1.0);

            ReadinessResult result = model.compute(thin, ComponentWeights.standard(), null);

            assertThat(result.raw()).isGreaterThan(90.0);
            assertThat(result.score()).isLessThan(65.0);
            assertThat(result.confidence()).isLessThan(0.65);
        }

        @Test
        @DisplayName("the ceiling lifts as evidence accumulates")
        void confidenceGrows() {
            assertThat(WeightedReadinessModel.confidenceFactor(0))
                    .isEqualTo(WeightedReadinessModel.CONFIDENCE_FLOOR_FACTOR);
            assertThat(WeightedReadinessModel.confidenceFactor(75))
                    .isGreaterThan(WeightedReadinessModel.confidenceFactor(12));
            assertThat(WeightedReadinessModel.confidenceFactor(150)).isEqualTo(1.0);
            // And never exceeds 1: more practice cannot make a score claim more than it measured.
            assertThat(WeightedReadinessModel.confidenceFactor(10_000)).isEqualTo(1.0);
        }

        @Test
        @DisplayName("the band is reported, not just the multiplier")
        void bandIsNamed() {
            assertThat(ConfidenceBand.of(5)).isEqualTo(ConfidenceBand.CALIBRATING);
            assertThat(ConfidenceBand.of(30)).isEqualTo(ConfidenceBand.LOW);
            assertThat(ConfidenceBand.of(100)).isEqualTo(ConfidenceBand.MODERATE);
            assertThat(ConfidenceBand.of(500)).isEqualTo(ConfidenceBand.HIGH);
        }

        @Test
        @DisplayName("the raw score is kept, because it explains why the score looks low")
        void rawIsRetained() {
            ReadinessResult result = model.compute(
                    new ReadinessInputs(List.of(strong("JavaScript")), 20, NOW, null,
                            0, 0, 20, 10, 10, List.of(80.0), List.of(1), 0.8),
                    ComponentWeights.standard(), null);

            assertThat(result.raw()).isGreaterThan(result.score());
        }
    }

    @Nested
    @DisplayName("individual components")
    class Components {

        @Test
        @DisplayName("coverage counts assessed topics, not topics touched once")
        void coverageNeedsEvidence() {
            SubjectEvidence touched = new SubjectEvidence("JS", 1.0, 10, 1, 0.9, 2.0, 6, 6, 60);
            SubjectEvidence covered = new SubjectEvidence("JS", 1.0, 10, 9, 0.9, 2.0, 90, 50, 60);

            double touchedScore = WeightedReadinessModel.coverage(withSubjects(touched));
            double coveredScore = WeightedReadinessModel.coverage(withSubjects(covered));

            assertThat(touchedScore).isLessThan(25.0);
            assertThat(coveredScore).isGreaterThan(70.0);
        }

        @Test
        @DisplayName("a subject with one topic and forty unseen questions is not covered")
        void coverageLooksAtTheBankToo() {
            // The loophole a topic-only measure leaves: one topic, assessed, so 100% by topic -
            // while thirty-eight of forty questions have never been seen.
            SubjectEvidence loophole = new SubjectEvidence("JS", 1.0, 1, 1, 0.9, 2.0, 6, 2, 40);

            assertThat(WeightedReadinessModel.coverage(withSubjects(loophole)))
                    .isLessThan(60.0);
        }

        @Test
        @DisplayName("accuracy weights harder questions more heavily")
        void accuracyWeightsDifficulty() {
            SubjectEvidence easyWins = new SubjectEvidence("JS", 1.0, 5, 5, 0.85, 1.0, 60, 30, 40);
            SubjectEvidence hardWins = new SubjectEvidence("JS", 1.0, 5, 5, 0.85, 4.0, 60, 30, 40);

            // Same correct rate, but one of them earned it on expert questions. The weighting
            // does not change a single-subject average, so compare against a weak second subject
            // to see the effect on the blend.
            double withEasy = WeightedReadinessModel.accuracy(
                    withSubjects(easyWins, weak("CSS")));
            double withHard = WeightedReadinessModel.accuracy(
                    withSubjects(hardWins, weak("CSS")));

            assertThat(withHard).isGreaterThan(withEasy);
        }

        @Test
        @DisplayName("retention with nothing tracked is zero, not perfect")
        void untrackedRetentionIsNotPerfect() {
            // The most flattering lie available: an empty schedule reported as perfect recall.
            assertThat(WeightedReadinessModel.retention(empty())).isZero();
        }

        @Test
        @DisplayName("retention falls as the backlog grows")
        void retentionTracksTheBacklog() {
            double clear = WeightedReadinessModel.retention(withReviews(0, 0, 100));
            double slipping = WeightedReadinessModel.retention(withReviews(30, 0, 100));
            double bad = WeightedReadinessModel.retention(withReviews(30, 20, 100));

            assertThat(clear).isEqualTo(100.0);
            assertThat(slipping).isLessThan(clear);
            // Badly overdue counts twice: it has probably been forgotten, not merely delayed.
            assertThat(bad).isLessThan(slipping);
        }

        @Test
        @DisplayName("consistency does not demand studying every single day")
        void consistencyAllowsRest() {
            // Ten days in fourteen is full marks. A metric that punishes a weekend off is one
            // people learn to ignore.
            assertThat(WeightedReadinessModel.consistency(withStudyDays(10))).isEqualTo(100.0);
            assertThat(WeightedReadinessModel.consistency(withStudyDays(14))).isEqualTo(100.0);
            assertThat(WeightedReadinessModel.consistency(withStudyDays(5))).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a mock decays, so an old strong result stops counting")
        void mockDecays() {
            double fresh = WeightedReadinessModel.mock(withMocks(List.of(80.0), List.of(1)));
            double stale = WeightedReadinessModel.mock(withMocks(List.of(80.0), List.of(90)));

            assertThat(fresh).isGreaterThan(70.0);
            assertThat(stale).isLessThan(15.0);
        }

        @Test
        @DisplayName("the best recent mock counts, not the average")
        void mockTakesTheBest() {
            // A mock sat while ill is not evidence about what you know.
            double best = WeightedReadinessModel.mock(
                    withMocks(List.of(35.0, 82.0), List.of(1, 2)));

            assertThat(best).isGreaterThan(75.0);
        }

        @Test
        @DisplayName("no mock sat is zero, and the lever says to sit one")
        void noMockIsZero() {
            assertThat(WeightedReadinessModel.mock(empty())).isZero();
        }

        @Test
        @DisplayName("depth is distinct from accuracy")
        void depthIsNotAccuracy() {
            // 95% accurate against easy items, and struggling badly at the target level.
            ReadinessInputs shallow = new ReadinessInputs(
                    List.of(new SubjectEvidence("JS", 1.0, 5, 5, 0.95, 1.0, 200, 40, 40)),
                    200, NOW, null, 0, 0, 100, 12, 10, List.of(), List.of(), 0.25);

            ReadinessResult result = model.compute(shallow, ComponentWeights.standard(), null);

            assertThat(result.components().get("accuracy")).isGreaterThan(85.0);
            // Someone who only answers easy questions has no depth, and a single percentage
            // would flatter exactly this learner.
            assertThat(result.components().get("depth")).isLessThan(40.0);
        }
    }

    @Nested
    @DisplayName("explaining itself")
    class Explainability {

        @Test
        @DisplayName("a score carries its components and the weights applied to them")
        void breakdownIsStored() {
            ReadinessResult result = model.compute(prepared(), ComponentWeights.standard(), null);

            assertThat(result.components()).containsOnlyKeys(
                    "coverage", "accuracy", "depth", "retention", "consistency", "mock");
            assertThat(result.weights()).hasSameSizeAs(result.components());

            // The score must be reconstructible by hand from what is stored, or the breakdown is
            // decoration rather than an explanation.
            double recomputed = result.components().entrySet().stream()
                    .mapToDouble(entry -> entry.getValue() * result.weights().get(entry.getKey()))
                    .sum();
            assertThat(recomputed * result.confidence())
                    .isCloseTo(result.score(), org.assertj.core.data.Offset.offset(0.5));
        }

        @Test
        @DisplayName("deltas against the previous snapshot are reported")
        void deltasAreComputed() {
            Map<String, Double> yesterday = Map.of(
                    "coverage", 60.0, "accuracy", 70.0, "depth", 50.0,
                    "retention", 90.0, "consistency", 80.0, "mock", 70.0,
                    "total", 68.0);

            ReadinessResult result = model.compute(prepared(), ComponentWeights.standard(),
                    yesterday);

            assertThat(result.deltas()).containsKey("total");
            assertThat(result.deltas()).containsKeys("coverage", "accuracy", "retention");
            assertThat(result.drivers()).isNotEmpty();
        }

        @Test
        @DisplayName("only changes worth reading are listed as drivers")
        void tinyChangesAreNotReported() {
            ReadinessResult first = model.compute(prepared(), ComponentWeights.standard(), null);

            Map<String, Double> barelyDifferent = new java.util.HashMap<>(first.components());
            barelyDifferent.put("total", first.score());

            ReadinessResult second = model.compute(prepared(), ComponentWeights.standard(),
                    barelyDifferent);

            // Six lines of "coverage +0.1" teaches the learner to skip the section, and then the
            // one line that mattered goes unread too.
            assertThat(second.drivers()).noneMatch(driver -> driver.contains("+0.0"));
        }

        @Test
        @DisplayName("the biggest lever points at the weakest weighted component")
        void leverFindsTheRealProblem() {
            // Everything reasonable except retention: a hundred items tracked, ninety due.
            ReadinessInputs backlog = new ReadinessInputs(
                    List.of(strong("JavaScript")), 300, NOW, null,
                    90, 40, 100, 12, 10, List.of(80.0), List.of(2), 0.80);

            ReadinessResult result = model.compute(backlog, ComponentWeights.standard(), null);

            assertThat(result.biggestLever().component()).isEqualTo("retention");
            assertThat(result.biggestLever().action()).contains("90 reviews");
            assertThat(result.biggestLever().estimatedGain()).isGreaterThan(0.0);
        }

        @Test
        @DisplayName("a learner who has never sat a mock is told to sit one")
        void leverSuggestsAMock() {
            ReadinessInputs noMock = new ReadinessInputs(
                    List.of(strong("JavaScript"), strong("React")), 300, NOW, null,
                    0, 0, 200, 12, 10, List.of(), List.of(), 0.82);

            ReadinessResult result = model.compute(noMock, ComponentWeights.standard(), null);

            assertThat(result.biggestLever().component()).isEqualTo("mock");
            assertThat(result.biggestLever().action()).contains("mock");
        }

        @Test
        @DisplayName("the estimated gain is a part of the shortfall, not the whole of it")
        void gainIsConservative() {
            ReadinessInputs backlog = new ReadinessInputs(
                    List.of(strong("JavaScript")), 300, NOW, null,
                    100, 50, 100, 12, 10, List.of(80.0), List.of(2), 0.80);

            ReadinessResult result = model.compute(backlog, ComponentWeights.standard(), null);
            double shortfall = 100.0 - result.components().get("retention");

            // Promising the whole shortfall makes the estimate a number nobody believes twice.
            assertThat(result.biggestLever().estimatedGain())
                    .isLessThan(shortfall * result.weights().get("retention"));
        }

        @Test
        @DisplayName("the headline says the confidence, not just the number")
        void headlineIsHonest() {
            ReadinessResult ready = model.compute(prepared(), ComponentWeights.standard(), null);
            assertThat(ready.headline()).contains("%").contains("confidence");

            ReadinessInputs thin = new ReadinessInputs(
                    List.of(strong("JS")), 6, NOW, null, 0, 0, 6, 2, 10,
                    List.of(), List.of(), 0.5);
            assertThat(model.compute(thin, ComponentWeights.standard(), null).headline())
                    .contains("calibrating");
        }
    }

    /* ------------------------------------------------------------------ builders */

    private ReadinessInputs withSubjects(SubjectEvidence... subjects) {
        int attempts = 0;
        for (SubjectEvidence subject : subjects) {
            attempts += subject.attempts();
        }
        return new ReadinessInputs(List.of(subjects), attempts, NOW, null,
                0, 0, 0, 0, 10, List.of(), List.of(), null);
    }

    private ReadinessInputs withReviews(int due, int overdue, int tracked) {
        return new ReadinessInputs(List.of(), 100, NOW, null, due, overdue, tracked,
                0, 10, List.of(), List.of(), null);
    }

    private ReadinessInputs withStudyDays(int days) {
        return new ReadinessInputs(List.of(), 100, NOW, null, 0, 0, 0, days, 10,
                List.of(), List.of(), null);
    }

    private ReadinessInputs withMocks(List<Double> scores, List<Integer> ages) {
        return new ReadinessInputs(List.of(), 100, NOW, null, 0, 0, 0, 0, 10,
                scores, ages, null);
    }
}
