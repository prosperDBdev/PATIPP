package com.patipp.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.patipp.scheduling.ReviewScheduler.Priority;
import com.patipp.scheduling.ReviewScheduler.SchedulerConfig;
import com.patipp.scheduling.ReviewState.Phase;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The scheduler, tested by advancing a clock.
 *
 * <p>No Spring and no database, so ninety days of study history is a loop that runs in
 * microseconds. That is the whole reason the module is kept pure: a scheduler is judged by its
 * behaviour over months, and behaviour over months is only testable if simulating months is
 * free.
 */
class Fsrs1SchedulerTest {

    private static final Instant START = Instant.parse("2026-01-01T09:00:00Z");

    private final Fsrs1Scheduler scheduler = new Fsrs1Scheduler();
    private final SchedulerConfig config = SchedulerConfig.standard();

    /** Answers an item at its due date, repeatedly, and returns the intervals it produced. */
    private List<Double> intervalsOver(int reviews, Grade grade) {
        ReviewState state = ReviewState.newItem("MEDIUM");
        Instant now = START;
        List<Double> intervals = new ArrayList<>();

        for (int i = 0; i < reviews; i++) {
            state = scheduler.next(state, grade, now, config);
            intervals.add(state.intervalDays());
            now = state.dueAt();
        }
        return intervals;
    }

    @Nested
    @DisplayName("learning a new item")
    class Learning {

        @Test
        @DisplayName("a new item goes through short steps before joining the curve")
        void walksTheSteps() {
            ReviewState state = ReviewState.newItem("MEDIUM");
            assertThat(state.phase()).isEqualTo(Phase.NEW);

            state = scheduler.next(state, Grade.GOOD, START, config);
            assertThat(state.phase()).isEqualTo(Phase.LEARNING);
            // Second step is a day, so it comes back tomorrow rather than in three weeks.
            assertThat(state.intervalDays()).isEqualTo(1.0);

            state = scheduler.next(state, Grade.GOOD, state.dueAt(), config);
            assertThat(state.phase()).isEqualTo(Phase.REVIEW);
            assertThat(state.reps()).isEqualTo(1);
        }

        @Test
        @DisplayName("EASY skips the remaining steps")
        void easyGraduatesImmediately() {
            ReviewState state = scheduler.next(ReviewState.newItem("MEDIUM"),
                    Grade.EASY, START, config);

            // Nothing is learned by showing something again in ten minutes that was instant.
            assertThat(state.phase()).isEqualTo(Phase.REVIEW);
            assertThat(state.intervalDays()).isGreaterThan(2.0);
        }

        @Test
        @DisplayName("AGAIN during learning brings it straight back")
        void againRestartsTheSteps() {
            ReviewState state = scheduler.next(ReviewState.newItem("MEDIUM"),
                    Grade.GOOD, START, config);
            state = scheduler.next(state, Grade.AGAIN, START.plus(Duration.ofMinutes(10)), config);

            assertThat(state.learningStep()).isZero();
            // Ten minutes, so it is seen again inside the same sitting.
            assertThat(state.intervalDays()).isCloseTo(10.0 / 1440.0, within(0.0001));
        }

        @Test
        @DisplayName("failing a new item is not counted as a lapse")
        void newItemsCannotLapse() {
            ReviewState state = scheduler.next(ReviewState.newItem("MEDIUM"),
                    Grade.AGAIN, START, config);

            // A lapse means forgetting something you had learned. You cannot forget what you
            // never knew, and counting it would make every new card look like a problem item.
            assertThat(state.lapses()).isZero();
            assertThat(state.phase()).isEqualTo(Phase.LEARNING);
        }

        @Test
        @DisplayName("a harder label starts harder, so its first interval is shorter")
        void authoredDifficultySeedsTheItem() {
            assertThat(ReviewState.newItem("EASY").difficulty())
                    .isLessThan(ReviewState.newItem("EXPERT").difficulty());
        }
    }

    @Nested
    @DisplayName("intervals over time")
    class Growth {

        @Test
        @DisplayName("consistently correct answers produce growing intervals, decelerating")
        void monotonicGrowth() {
            // The roadmap's exit criterion. Growth is monotonic up to the ceiling, where it
            // necessarily flattens - an interval cannot keep growing past its own cap.
            List<Double> intervals = intervalsOver(12, Grade.GOOD);

            for (int i = 2; i < intervals.size(); i++) {
                assertThat(intervals.get(i))
                        .as("review %d should wait at least as long as review %d", i + 1, i)
                        .isGreaterThanOrEqualTo(intervals.get(i - 1));
            }
            // It must actually reach a useful spacing rather than creeping...
            assertThat(intervals.get(intervals.size() - 1)).isGreaterThan(90.0);

            // ...but not in a handful of reviews. Seven correct answers is not grounds for not
            // asking again for a year, which is what the undamped formula produced.
            assertThat(intervals.get(6)).isLessThan(SchedulerConfig.DEFAULT_MAXIMUM_DAYS);
        }

        @Test
        @DisplayName("growth decelerates as an item becomes well established")
        void growthSaturates() {
            List<Double> intervals = intervalsOver(10, Grade.GOOD);

            double earlyRatio = intervals.get(3) / intervals.get(2);
            double lateRatio = intervals.get(7) / intervals.get(6);

            // Without the saturation term these ratios are identical and the interval explodes.
            assertThat(lateRatio).isLessThan(earlyRatio);
        }

        @Test
        @DisplayName("EASY grows faster than GOOD, which grows faster than HARD")
        void gradeChangesTheRate() {
            double hard = intervalsOver(6, Grade.HARD).get(5);
            double good = intervalsOver(6, Grade.GOOD).get(5);
            double easy = intervalsOver(6, Grade.EASY).get(5);

            assertThat(hard).isLessThan(good);
            assertThat(good).isLessThan(easy);
        }

        @Test
        @DisplayName("recalling something almost forgotten is worth more than cramming")
        void spacingEffect() {
            ReviewState settled = new ReviewState(Phase.REVIEW, 10.0, 5.0,
                    START, START, 10.0, 3, 0, 3, 0, 0);

            // Reviewed an hour after the last look: retrievability is ~1, so no bonus.
            ReviewState crammed = scheduler.next(settled, Grade.GOOD,
                    START.plus(Duration.ofHours(1)), config);

            // Reviewed well past due, when recall was genuinely uncertain.
            ReviewState spaced = scheduler.next(settled, Grade.GOOD,
                    START.plus(Duration.ofDays(25)), config);

            // Without this the scheduler rewards cramming, which is the opposite of the point.
            assertThat(spaced.stability()).isGreaterThan(crammed.stability());
        }

        @Test
        @DisplayName("an item the learner finds hard grows more slowly than an easy one")
        void difficultyDampensGrowth() {
            ReviewState easy = new ReviewState(Phase.REVIEW, 10.0, 2.0,
                    START, START, 10.0, 3, 0, 3, 0, 0);
            ReviewState hard = new ReviewState(Phase.REVIEW, 10.0, 9.0,
                    START, START, 10.0, 3, 0, 3, 0, 0);

            Instant due = START.plus(Duration.ofDays(10));
            assertThat(scheduler.next(easy, Grade.GOOD, due, config).stability())
                    .isGreaterThan(scheduler.next(hard, Grade.GOOD, due, config).stability());
        }

        @Test
        @DisplayName("intervals are capped, so a known item still resurfaces eventually")
        void intervalIsCapped() {
            ReviewState state = new ReviewState(Phase.REVIEW, 5000.0, 3.0,
                    START, START, 400.0, 30, 0, 30, 0, 0);

            assertThat(scheduler.next(state, Grade.EASY, START.plus(Duration.ofDays(400)), config)
                    .intervalDays())
                    .isLessThanOrEqualTo(SchedulerConfig.DEFAULT_MAXIMUM_DAYS);
        }

        @Test
        @DisplayName("asking for higher retention shortens every interval")
        void retentionIsTheTradeOff() {
            ReviewState state = new ReviewState(Phase.REVIEW, 20.0, 5.0,
                    START, START, 20.0, 5, 0, 5, 0, 0);
            Instant due = START.plus(Duration.ofDays(20));

            double relaxed = scheduler.next(state, Grade.GOOD, due,
                    new SchedulerConfig(0.80, null, 365)).intervalDays();
            double strict = scheduler.next(state, Grade.GOOD, due,
                    new SchedulerConfig(0.95, null, 365)).intervalDays();

            // More reviews for less forgetting, which is the honest statement of the trade.
            assertThat(strict).isLessThan(relaxed);
        }

        @Test
        @DisplayName("at the default target, the interval equals the stability")
        void defaultTargetIsTheIdentity() {
            // Stability is defined as "days until recall falls to 90%", so scheduling for 90%
            // retention must reproduce it exactly. If this drifts, the word has lost meaning.
            assertThat(Fsrs1Scheduler.intervalFor(17.5, config)).isCloseTo(17.5, within(0.001));
        }
    }

    @Nested
    @DisplayName("forgetting")
    class Lapses {

        private final ReviewState established = new ReviewState(Phase.REVIEW, 40.0, 5.0,
                START, START, 40.0, 6, 0, 6, 0, 0);

        @Test
        @DisplayName("a lapse collapses the interval and brings it back today")
        void lapseCollapsesTheInterval() {
            ReviewState lapsed = scheduler.next(established, Grade.AGAIN,
                    START.plus(Duration.ofDays(40)), config);

            assertThat(lapsed.phase()).isEqualTo(Phase.RELEARNING);
            assertThat(lapsed.lapses()).isEqualTo(1);
            assertThat(lapsed.stability()).isLessThan(established.stability() / 2);
            assertThat(lapsed.intervalDays()).isLessThan(1.0);
            assertThat(lapsed.consecutiveCorrect()).isZero();
        }

        @Test
        @DisplayName("a relapsed item works back up rather than resuming where it was")
        void relearningRebuilds() {
            ReviewState lapsed = scheduler.next(established, Grade.AGAIN,
                    START.plus(Duration.ofDays(40)), config);
            ReviewState recovering = scheduler.next(lapsed, Grade.GOOD,
                    lapsed.dueAt(), config);

            // Back on the curve, but nowhere near the forty days it had before.
            assertThat(recovering.phase()).isIn(Phase.LEARNING, Phase.REVIEW);
            assertThat(recovering.intervalDays()).isLessThan(established.intervalDays());
        }

        @Test
        @DisplayName("stability never falls to zero, so an item cannot get stuck")
        void stabilityHasAFloor() {
            ReviewState state = established;
            Instant now = START;

            for (int i = 0; i < 30; i++) {
                state = scheduler.next(state, Grade.AGAIN, now, config);
                now = state.dueAt();
            }
            assertThat(state.stability()).isGreaterThan(0.0);
            assertThat(state.intervalDays()).isGreaterThan(0.0);
        }
    }

    @Nested
    @DisplayName("priority")
    class Priorities {

        @Test
        @DisplayName("repeatedly forgotten items come first")
        void repeatedLapsesAreHigh() {
            ReviewState state = new ReviewState(Phase.REVIEW, 3.0, 7.0,
                    START, START, 3.0, 4, 2, 1, 0, 0);

            assertThat(scheduler.priority(state, START.plus(Duration.ofDays(3))))
                    .isEqualTo(Priority.HIGH);
        }

        @Test
        @DisplayName("badly overdue is high, because the interval no longer describes anything")
        void severelyOverdueIsHigh() {
            ReviewState state = new ReviewState(Phase.REVIEW, 10.0, 5.0,
                    START, START, 10.0, 5, 0, 5, 0, 0);

            assertThat(scheduler.priority(state, START.plus(Duration.ofDays(30))))
                    .isEqualTo(Priority.HIGH);
        }

        @Test
        @DisplayName("a well-known item due today can safely wait")
        void comfortableItemsAreLow() {
            ReviewState state = new ReviewState(Phase.REVIEW, 60.0, 3.0,
                    START, START, 60.0, 8, 0, 5, 0, 0);

            assertThat(scheduler.priority(state, START.plus(Duration.ofDays(60))))
                    .isEqualTo(Priority.LOW);
        }

        @Test
        @DisplayName("a suspended item is never due and never urgent")
        void suspendedIsItsOwnBand() {
            ReviewState state = new ReviewState(Phase.SUSPENDED, 5.0, 5.0,
                    START, START, 5.0, 3, 0, 3, 0, 0);

            assertThat(scheduler.priority(state, START.plus(Duration.ofDays(90))))
                    .isEqualTo(Priority.SUSPENDED);
            assertThat(state.isDue(START.plus(Duration.ofDays(90)))).isFalse();
            // And answering it changes nothing: un-suspending is an explicit act.
            assertThat(scheduler.next(state, Grade.GOOD, START, config)).isEqualTo(state);
        }
    }

    @Nested
    @DisplayName("interval previews")
    class Previews {

        @Test
        @DisplayName("every button says what it would schedule, and they are ordered")
        void previewsAreOrdered() {
            ReviewState state = new ReviewState(Phase.REVIEW, 12.0, 5.0,
                    START, START, 12.0, 4, 0, 4, 0, 0);
            Instant due = START.plus(Duration.ofDays(12));

            Map<Grade, Double> preview = scheduler.previewIntervals(state, due, config);

            assertThat(preview).containsOnlyKeys(Grade.AGAIN, Grade.HARD, Grade.GOOD, Grade.EASY);
            assertThat(preview.get(Grade.AGAIN)).isLessThan(preview.get(Grade.HARD));
            assertThat(preview.get(Grade.HARD)).isLessThan(preview.get(Grade.GOOD));
            assertThat(preview.get(Grade.GOOD)).isLessThan(preview.get(Grade.EASY));
        }

        @Test
        @DisplayName("a preview is exactly what pressing the button does")
        void previewMatchesReality() {
            ReviewState state = new ReviewState(Phase.REVIEW, 9.0, 4.5,
                    START, START, 9.0, 3, 0, 3, 0, 0);
            Instant due = START.plus(Duration.ofDays(9));

            Map<Grade, Double> preview = scheduler.previewIntervals(state, due, config);

            // A preview that does not match is worse than no preview: it teaches the learner
            // the numbers on screen are decorative.
            for (Grade grade : Grade.values()) {
                assertThat(scheduler.next(state, grade, due, config).intervalDays())
                        .as("preview for %s", grade)
                        .isEqualTo(preview.get(grade));
            }
        }
    }

    @Nested
    @DisplayName("ninety days of study")
    class Simulation {

        @Test
        @DisplayName("a learner who mostly remembers ends up reviewing rarely")
        void knownItemSettlesDown() {
            ReviewState state = ReviewState.newItem("MEDIUM");
            Instant now = START;
            Instant end = START.plus(Duration.ofDays(90));
            int reviews = 0;

            while (now.isBefore(end)) {
                state = scheduler.next(state, Grade.GOOD, now, config);
                reviews++;
                now = state.dueAt();
            }

            // Roughly a dozen reviews to hold one fact for three months, not ninety.
            assertThat(reviews).isBetween(4, 15);
            assertThat(state.intervalDays()).isGreaterThan(20.0);
        }

        @Test
        @DisplayName("a learner who keeps forgetting keeps seeing it")
        void forgottenItemKeepsComingBack() {
            ReviewState state = ReviewState.newItem("HARD");
            Instant now = START;
            Instant end = START.plus(Duration.ofDays(90));
            int reviews = 0;

            // Alternating: forgets it, relearns it, forgets it again.
            while (now.isBefore(end) && reviews < 500) {
                state = scheduler.next(state, reviews % 2 == 0 ? Grade.AGAIN : Grade.GOOD,
                        now, config);
                reviews++;
                now = state.dueAt();
            }

            assertThat(reviews).isGreaterThan(20);
            assertThat(state.lapses()).isGreaterThan(3);
            // And it never escapes to a long interval on the strength of alternate successes.
            assertThat(state.intervalDays()).isLessThan(10.0);
        }
    }
}
