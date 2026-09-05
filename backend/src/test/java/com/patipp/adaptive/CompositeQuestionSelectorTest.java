package com.patipp.adaptive;

import static org.assertj.core.api.Assertions.assertThat;

import com.patipp.adaptive.LearnerModel.ItemState;
import com.patipp.adaptive.LearnerModel.RecentWindow;
import com.patipp.adaptive.LearnerModel.TopicKey;
import com.patipp.adaptive.LearnerModel.TopicState;
import com.patipp.adaptive.QuestionSelector.Candidate;
import com.patipp.adaptive.QuestionSelector.Chosen;
import com.patipp.adaptive.QuestionSelector.Request;
import com.patipp.adaptive.QuestionSelector.Selection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Synthetic learners, built by hand.
 *
 * <p>This is what the pure engine buys: a learner strong in JavaScript and weak in React
 * Native can be constructed in four lines and the selection checked exactly, with no fixture,
 * no database and no session. The roadmap's exit criterion for this phase is one of the tests
 * below.
 */
class CompositeQuestionSelectorTest {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    private final CompositeQuestionSelector selector = new CompositeQuestionSelector();

    private final UUID user = UUID.randomUUID();
    private final UUID space = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    private final TopicKey javascript = TopicKey.of(subject, UUID.randomUUID());
    private final TopicKey reactNative = TopicKey.of(subject, UUID.randomUUID());
    private final TopicKey css = TopicKey.of(subject, UUID.randomUUID());

    /** Deterministic: temperature zero, so the assertions are about scoring, not luck. */
    private static Request deterministic(int length) {
        return new Request(length, 1L, null, 0.0, false);
    }

    @Test
    @DisplayName("a learner weak in one topic is served that topic as often as the rules allow")
    void weaknessDominatesSelection() {
        // The roadmap's exit criterion. Strong in JavaScript, weak in React Native, both
        // measured well past the confidence floor.
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1500, 0.93, 60, 0.8, 0),
                reactNative, topic(reactNative, 1150, 0.44, 40, 0.8, 0)));

        // A realistic bank: a spread of difficulties in each topic, not one rating repeated.
        // With a single rating per topic there is nothing near the target for the weak topic,
        // and difficulty fit would rightly send the engine elsewhere.
        List<Candidate> pool = new ArrayList<>();
        pool.addAll(spread(javascript, 20, 1100, 1700));
        pool.addAll(spread(reactNative, 20, 800, 1400));

        Selection selection = selector.select(model, pool, deterministic(10));

        long weak = countIn(selection, reactNative, pool);
        long strong = countIn(selection, javascript, pool);

        // Its full allowance under the diversity cap - the engine cannot serve more than 40%
        // of a session from one topic, and it should be taking all of it here.
        assertThat(weak).isGreaterThanOrEqualTo(4);
        assertThat(weak).isGreaterThan(strong / 2);
    }

    @Test
    @DisplayName("no single topic takes over the session")
    void diversityIsEnforced() {
        // React Native is so weak that, unchecked, it would take every slot. It must not:
        // twelve questions on your worst topic is how a learner stops opening the app.
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1400, 0.95, 60, 0.9, 0),
                reactNative, topic(reactNative, 1000, 0.20, 40, 0.2, 3),
                css, topic(css, 1300, 0.85, 30, 0.9, 0)));

        List<Candidate> pool = new ArrayList<>();
        pool.addAll(candidates(javascript, 20, 1250));
        pool.addAll(candidates(reactNative, 20, 1000));
        pool.addAll(candidates(css, 20, 1200));

        Selection selection = selector.select(model, pool, deterministic(10));

        assertThat(countIn(selection, reactNative, pool)).isLessThanOrEqualTo(4);
        // And the session still spans more than one area.
        assertThat(distinctTopics(selection, pool)).isGreaterThan(1);
    }

    @Test
    @DisplayName("a single-topic drill is allowed to be a single topic")
    void drillOptsOutOfDiversity() {
        LearnerModel model = model(Map.of(
                reactNative, topic(reactNative, 1000, 0.30, 40, 0.5, 0)));

        List<Candidate> pool = new ArrayList<>(candidates(reactNative, 20, 1000));

        Selection selection = selector.select(model, pool,
                new Request(10, 1L, null, 0.0, true));

        assertThat(selection.items()).hasSize(10);
    }

    @Test
    @DisplayName("untouched topics are pulled in rather than ignored")
    void coverageGapIsRespected() {
        // Two topics practised heavily, one never opened. A pure weakness engine would never
        // serve the third, because there is no evidence it is a problem - which is exactly
        // how a learner arrives at the exam having never seen a fifth of the syllabus.
        Map<TopicKey, TopicState> topics = new LinkedHashMap<>();
        topics.put(javascript, topic(javascript, 1400, 0.90, 60, 1.0, 0));
        topics.put(css, topic(css, 1350, 0.88, 40, 1.0, 0));
        topics.put(reactNative, topic(reactNative, 1200, 0.0, 0, 0.0, 0));

        LearnerModel model = model(topics);

        List<Candidate> pool = new ArrayList<>();
        pool.addAll(candidates(javascript, 20, 1250));
        pool.addAll(candidates(css, 20, 1200));
        pool.addAll(candidates(reactNative, 20, 1200));

        Selection selection = selector.select(model, pool, deterministic(10));

        assertThat(countIn(selection, reactNative, pool)).isGreaterThan(0);
    }

    @Test
    @DisplayName("difficulty lands near the target rather than at the extremes")
    void difficultyFitsTheLearner() {
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1400, 0.75, 60, 0.6, 0)));

        List<Candidate> pool = new ArrayList<>();
        // A spread from trivial to impossible for a learner rated 1400.
        for (int rating = 800; rating <= 2000; rating += 100) {
            pool.addAll(candidates(javascript, 3, rating));
        }

        Selection selection = selector.select(model, pool, deterministic(8));

        double meanExpectation = selection.items().stream()
                .mapToDouble(Chosen::expectation)
                .average()
                .orElseThrow();

        // Around the 78% target, not 99% (pointless) and not 20% (demoralising).
        assertThat(meanExpectation).isBetween(0.60, 0.92);
    }

    @Test
    @DisplayName("a struggling learner is given something they can answer")
    void recoveryModeEngages() {
        // Four of the last five wrong. The engine must ease off, not press on.
        LearnerModel model = new LearnerModel(user, space, 1200,
                Map.of(javascript, topic(javascript, 1200, 0.40, 40, 0.5, 2)),
                Map.of(),
                new RecentWindow(List.of(
                        outcome(javascript, false), outcome(javascript, false),
                        outcome(javascript, true), outcome(javascript, false),
                        outcome(javascript, false))),
                NOW);

        List<Candidate> pool = new ArrayList<>();
        for (int rating = 700; rating <= 1600; rating += 100) {
            pool.addAll(candidates(javascript, 3, rating));
        }

        Selection selection = selector.select(model, pool, deterministic(5));

        assertThat(selection.diagnostics()).containsEntry("recovery", true);
        assertThat(selection.items().get(0).reason()).isEqualTo("RECOVERY");
        // Comfortably within reach: the point is to break the run, not to keep testing.
        assertThat(selection.items().get(0).expectation()).isGreaterThan(0.75);
    }

    @Test
    @DisplayName("there is a winnable question at least every six")
    void winCadenceIsHonoured() {
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1300, 0.50, 40, 0.5, 0)));

        List<Candidate> pool = new ArrayList<>();
        pool.addAll(candidates(javascript, 30, 1500));  // hard
        pool.addAll(candidates(javascript, 30, 800));   // comfortable

        Selection selection = selector.select(model, pool,
                new Request(12, 1L, null, 0.0, true));

        int sinceWin = 0;
        int longestDrought = 0;
        for (Chosen chosen : selection.items()) {
            sinceWin = chosen.expectation() >= CompositeQuestionSelector.WIN_EXPECTATION
                    ? 0 : sinceWin + 1;
            longestDrought = Math.max(longestDrought, sinceWin);
        }

        assertThat(longestDrought).isLessThan(CompositeQuestionSelector.WIN_CADENCE);
    }

    @Test
    @DisplayName("a question answered minutes ago is not served again")
    void justSeenIsPenalised() {
        UUID justSeen = UUID.randomUUID();

        LearnerModel model = new LearnerModel(user, space, 1200,
                Map.of(javascript, topic(javascript, 1200, 0.50, 40, 0.5, 0)),
                Map.of(justSeen, new ItemState(justSeen, 1, 0,
                        NOW.minusSeconds(600), false, null, 0.0)),
                RecentWindow.empty(),
                NOW);

        List<Candidate> pool = new ArrayList<>();
        pool.add(new Candidate(justSeen, UUID.randomUUID(), javascript, 1200, "MEDIUM", 60));
        pool.addAll(candidates(javascript, 10, 1200));

        Selection selection = selector.select(model, pool,
                new Request(5, 1L, null, 0.0, true));

        assertThat(selection.items()).noneMatch(chosen -> chosen.questionId().equals(justSeen));
    }

    @Test
    @DisplayName("a brand-new space calibrates instead of pretending to adapt")
    void coldStartCalibrates() {
        // Four attempts is not a learner model, it is noise. The engine says so.
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1200, 0.25, 4, 0.1, 2)));

        List<Candidate> pool = new ArrayList<>();
        pool.addAll(candidates(javascript, 10, 1200));
        pool.addAll(candidates(reactNative, 10, 1200));
        pool.addAll(candidates(css, 10, 1200));

        Selection selection = selector.select(model, pool, deterministic(9));

        assertThat(selection.diagnostics()).containsEntry("calibrating", true);
        assertThat(selection.items()).allMatch(chosen -> chosen.reason().equals("CALIBRATION"));
        // Spread across the syllabus rather than piled onto the one topic with any history.
        assertThat(distinctTopics(selection, pool)).isEqualTo(3);
    }

    @Test
    @DisplayName("every chosen question explains itself")
    void selectionIsExplainable() {
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1400, 0.92, 60, 0.9, 0),
                reactNative, topic(reactNative, 1100, 0.41, 40, 0.3, 2)));

        List<Candidate> pool = new ArrayList<>();
        pool.addAll(candidates(javascript, 15, 1300));
        pool.addAll(candidates(reactNative, 15, 1100));

        Selection selection = selector.select(model, pool, deterministic(8));

        assertThat(selection.items()).allSatisfy(chosen -> {
            assertThat(chosen.reason()).isNotBlank();
            assertThat(chosen.explanation()).isNotBlank();
            assertThat(chosen.components()).containsKeys(
                    "weakness", "difficultyFit", "coverageGap", "freshness", "expectation");
        });
        // An engine that cannot say why is one you stop trusting, and then the feature is dead.
        assertThat(selection.items()).anyMatch(chosen -> chosen.reason().equals("WEAK_TOPIC"));
    }

    @Test
    @DisplayName("the same seed gives the same session, a different one does not")
    void samplingIsSeededButVaried() {
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1300, 0.70, 60, 0.5, 0)));
        List<Candidate> pool = new ArrayList<>(candidates(javascript, 60, 1200));

        List<UUID> first = ids(selector.select(model, pool,
                new Request(10, 99L, null, 0.5, true)));
        List<UUID> same = ids(selector.select(model, pool,
                new Request(10, 99L, null, 0.5, true)));
        List<UUID> other = ids(selector.select(model, pool,
                new Request(10, 12345L, null, 0.5, true)));

        assertThat(first).isEqualTo(same);
        // Otherwise it is the same session every morning, which is bad for recall and worse
        // for wanting to open it.
        assertThat(first).isNotEqualTo(other);
    }

    @Test
    @DisplayName("a pool smaller than the session returns what exists rather than failing")
    void shortPoolIsNotAnError() {
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1300, 0.70, 60, 0.5, 0)));

        Selection selection = selector.select(model,
                new ArrayList<>(candidates(javascript, 3, 1200)), deterministic(20));

        assertThat(selection.items()).hasSize(3);
    }

    @Test
    @DisplayName("an empty pool is an empty selection, not an exception")
    void emptyPoolIsHandled() {
        Selection selection = selector.select(
                LearnerModel.empty(user, space, NOW), List.of(), deterministic(10));

        assertThat(selection.items()).isEmpty();
        assertThat(selection.diagnostics()).containsEntry("reason", "EMPTY_POOL");
    }

    @Test
    @DisplayName("no question is served twice in one session")
    void selectionIsWithoutReplacement() {
        LearnerModel model = model(Map.of(
                javascript, topic(javascript, 1300, 0.70, 60, 0.5, 0)));

        Selection selection = selector.select(model,
                new ArrayList<>(candidates(javascript, 30, 1200)),
                new Request(20, 5L, null, 0.4, true));

        assertThat(ids(selection)).doesNotHaveDuplicates();
    }

    /* ------------------------------------------------------------------ builders */

    private LearnerModel model(Map<TopicKey, TopicState> topics) {
        return new LearnerModel(user, space, 1200, topics, Map.of(), RecentWindow.empty(), NOW);
    }

    private TopicState topic(TopicKey key, double ability, double accuracy, int attempts,
                             double coverage, int consecutiveWrong) {
        return new TopicState(key, 1.0, ability, accuracy, accuracy, attempts,
                (int) Math.round(attempts * accuracy), coverage, consecutiveWrong,
                NOW.minusSeconds(3600),
                consecutiveWrong > 0 ? NOW.minusSeconds(1800) : null,
                MasteryLevel.classify(attempts, accuracy));
    }

    /** A topic's worth of questions spanning a range of difficulty, as a real bank does. */
    private List<Candidate> spread(TopicKey topic, int count, double from, double to) {
        List<Candidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            double rating = from + (to - from) * i / Math.max(1, count - 1);
            candidates.add(new Candidate(UUID.randomUUID(), UUID.randomUUID(), topic,
                    rating, "MEDIUM", 60));
        }
        return candidates;
    }

    private List<Candidate> candidates(TopicKey topic, int count, double rating) {
        List<Candidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candidates.add(new Candidate(UUID.randomUUID(), UUID.randomUUID(), topic,
                    rating, "MEDIUM", 60));
        }
        return candidates;
    }

    private RecentWindow.Outcome outcome(TopicKey topic, boolean correct) {
        return new RecentWindow.Outcome(UUID.randomUUID(), topic, correct, 0.5, NOW);
    }

    private static List<UUID> ids(Selection selection) {
        return selection.items().stream().map(Chosen::questionId).toList();
    }

    private static long countIn(Selection selection, TopicKey topic, List<Candidate> pool) {
        Map<UUID, TopicKey> topics = new HashMap<>();
        pool.forEach(candidate -> topics.put(candidate.questionId(), candidate.topic()));
        return selection.items().stream()
                .filter(chosen -> topic.equals(topics.get(chosen.questionId())))
                .count();
    }

    private static long distinctTopics(Selection selection, List<Candidate> pool) {
        Map<UUID, TopicKey> topics = new HashMap<>();
        pool.forEach(candidate -> topics.put(candidate.questionId(), candidate.topic()));
        return selection.items().stream()
                .map(chosen -> topics.get(chosen.questionId()))
                .distinct()
                .count();
    }
}
