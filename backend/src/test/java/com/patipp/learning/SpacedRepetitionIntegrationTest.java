package com.patipp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.learning.domain.LearningState;
import com.patipp.learning.domain.LearningStateRepository;
import com.patipp.scheduling.ReviewState;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Spaced repetition against a real database.
 *
 * <p>The scheduler's own arithmetic is unit-tested in {@code com.patipp.scheduling} without
 * Spring. What is checked here is what only appears once the halves are joined: that every
 * format reaches the schedule and not just flashcards, that a review session serves what is
 * due, and that the schedule is genuinely derived — droppable and rebuildable from the log.
 *
 * <p>Uses a movable clock, because a review due in eleven days cannot be tested by waiting.
 */
@Import(SpacedRepetitionIntegrationTest.MovableClockConfig.class)
class SpacedRepetitionIntegrationTest extends IntegrationTest {

    @TestConfiguration
    static class MovableClockConfig {
        @Bean
        @Primary
        MovableClock movableClock() {
            // Starts at the real time: JWT validation uses the system clock, so a frozen past
            // would make every access token look expired before the test reached its subject.
            return new MovableClock(Instant.now());
        }
    }

    static class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            this.now = now.plus(by);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }

    @Autowired
    private MovableClock clock;

    @Autowired
    private LearningStateRepository states;

    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private String spaceId;
    private String subjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Reviewer");
        spaceId = createSpace("ACADEMIC_EXAM", "Review space");
        subjectId = createSubject("JavaScript");
    }

    // ------------------------------------------------------------------ every format schedules

    @Test
    @DisplayName("an ordinary multiple-choice answer enters the review schedule")
    void mcqIsScheduled() throws Exception {
        createMcq("Which keyword is block scoped?");
        String sessionId = startSession("PRACTICE", 1);

        MvcResult answered = answer(sessionId, 0, true, 30_000, null);

        // The whole point of deriving a grade: without it, reviewing a fact as a flashcard
        // counted towards retention and answering it as an MCQ did not.
        assertThat(Json.readString(answered.getResponse().getContentAsString(), "review.grade"))
                .isIn("GOOD", "EASY");
        assertThat(Json.readString(answered.getResponse().getContentAsString(), "review.derived"))
                .isEqualTo("true");

        assertThat(onlyState().phase()).isIn(ReviewState.Phase.LEARNING, ReviewState.Phase.REVIEW);
        assertThat(onlyState().dueAt()).isNotNull();
    }

    @Test
    @DisplayName("a correct but very slow answer is scheduled as Hard, not Good")
    void slowCorrectIsHard() throws Exception {
        createMcq("Slow one");
        String sessionId = startSession("PRACTICE", 1);

        // Estimated at 60 seconds, answered in four minutes. Getting there eventually is not
        // recall, and scheduling it as recall is how a fact quietly disappears for three weeks.
        MvcResult answered = answer(sessionId, 0, true, 240_000, null);

        assertThat(Json.readString(answered.getResponse().getContentAsString(), "review.grade"))
                .isEqualTo("HARD");
    }

    @Test
    @DisplayName("reported doubt outranks a fast answer")
    void lowConfidenceBeatsSpeed() throws Exception {
        createMcq("Lucky guess");
        String sessionId = startSession("PRACTICE", 1);

        // Answered in three seconds and reported as a guess. This is the lucky guess the whole
        // derivation exists to catch.
        MvcResult answered = answer(sessionId, 0, true, 3_000, 1);

        assertThat(Json.readString(answered.getResponse().getContentAsString(), "review.grade"))
                .isEqualTo("HARD");
    }

    @Test
    @DisplayName("a flashcard's own grade is used as given, not re-derived")
    void flashcardGradeIsAuthoritative() throws Exception {
        createFlashcard("Event loop", "Runs queued callbacks when the stack is empty");
        String sessionId = startSession("PRACTICE", 1);

        MvcResult answered = mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"grade":2},"responseTimeMs":1000}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String body = answered.getResponse().getContentAsString();
        // Three seconds would have derived EASY. The learner said Hard, and the learner wins.
        assertThat(Json.readString(body, "review.grade")).isEqualTo("HARD");
        assertThat(Json.readString(body, "review.derived")).isEqualTo("false");
    }

    // ------------------------------------------------------------------ intervals

    @Test
    @DisplayName("Again brings a card back inside the same session")
    void againComesBackImmediately() throws Exception {
        createFlashcard("Hoisting", "Declarations are processed before execution");
        String sessionId = startSession("PRACTICE", 1);

        MvcResult answered = mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"grade":1},"responseTimeMs":8000}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        double interval = Double.parseDouble(
                Json.readString(answered.getResponse().getContentAsString(), "review.intervalDays"));

        // Ten minutes. The point of a lapse is to see it again now, not tomorrow.
        assertThat(interval).isLessThan(0.02);
    }

    @Test
    @DisplayName("the previewed interval is exactly what pressing the button does")
    void previewMatchesReality() throws Exception {
        String questionId = createFlashcard("Closures", "A function plus its captured scope");

        MvcResult previewed = mockMvc.perform(get("/api/v1/spaces/" + spaceId
                        + "/questions/" + questionId + "/interval-preview")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.AGAIN").exists())
                .andExpect(jsonPath("$.EASY").exists())
                .andReturn();

        String body = previewed.getResponse().getContentAsString();
        double promisedGood = Double.parseDouble(Json.readString(body, "GOOD"));

        String sessionId = startSession("PRACTICE", 1);
        MvcResult answered = mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"grade":3},"responseTimeMs":5000}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        // A preview that only approximates is worse than none: it teaches the learner that the
        // numbers on screen are decorative.
        assertThat(Double.parseDouble(
                Json.readString(answered.getResponse().getContentAsString(), "review.intervalDays")))
                .isEqualTo(promisedGood);
    }

    @Test
    @DisplayName("repeated success pushes the interval out")
    void intervalsGrow() throws Exception {
        createFlashcard("Prototypes", "Objects delegate to their prototype");

        double previous = 0;
        for (int i = 0; i < 4; i++) {
            String sessionId = startSession("PRACTICE", 1);
            MvcResult answered = mockMvc.perform(post(url("/" + sessionId + "/answers"))
                            .header(HttpHeaders.AUTHORIZATION, user.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"position":0,"answer":{"grade":3},"responseTimeMs":4000}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn();

            double interval = Double.parseDouble(Json.readString(
                    answered.getResponse().getContentAsString(), "review.intervalDays"));
            assertThat(interval).as("review %d", i + 1).isGreaterThanOrEqualTo(previous);
            previous = interval;

            mockMvc.perform(post(url("/" + sessionId + "/complete"))
                    .header(HttpHeaders.AUTHORIZATION, user.bearer()));
            // Move to the due date, so the next review is a real one rather than cramming.
            clock.advance(Duration.ofMinutes((long) Math.max(1, interval * 1440)));
        }

        assertThat(previous).isGreaterThan(1.0);
    }

    // ------------------------------------------------------------------ review sessions

    @Test
    @DisplayName("review debt is reported, and reads as a sentence")
    void reviewDebtIsReported() throws Exception {
        mockMvc.perform(get(spaceUrl("/review-debt"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.due").value(0))
                .andExpect(jsonPath("$.summary").value(
                        org.hamcrest.Matchers.containsString("Nothing due")));

        createFlashcard("Debt", "Something to review");
        String sessionId = startSession("PRACTICE", 1);
        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"position":0,"answer":{"grade":3},"responseTimeMs":4000}
                        """));
        mockMvc.perform(post(url("/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));

        // Not due yet.
        mockMvc.perform(get(spaceUrl("/review-debt"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.due").value(0))
                .andExpect(jsonPath("$.tracked").value(1));

        clock.advance(Duration.ofDays(30));

        mockMvc.perform(get(spaceUrl("/review-debt"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.due").value(1))
                .andExpect(jsonPath("$.summary").value(
                        org.hamcrest.Matchers.containsString("ready to review")));
    }

    @Test
    @DisplayName("a review session serves what is due, most overdue first")
    void reviewSessionServesDueItems() throws Exception {
        // Three cards, answered so they schedule, then time moves past all of them.
        for (int i = 0; i < 3; i++) {
            createFlashcard("Card " + i, "Back " + i);
        }
        String warmUp = startSession("PRACTICE", 3);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post(url("/" + warmUp + "/answers"))
                    .header(HttpHeaders.AUTHORIZATION, user.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"position":%d,"answer":{"grade":3},"responseTimeMs":4000}
                            """.formatted(i)));
        }
        mockMvc.perform(post(url("/" + warmUp + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));

        clock.advance(Duration.ofDays(60));

        MvcResult review = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"FLASHCARD_REVIEW","length":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("FLASHCARD_REVIEW"))
                .andExpect(jsonPath("$.immediateFeedback").value(true))
                // Untimed: rushing a review turns recall practice into recognition practice.
                .andExpect(jsonPath("$.deadlineAt").doesNotExist())
                .andReturn();

        assertThat(Json.readString(review.getResponse().getContentAsString(),
                "currentItem.selectionReason.reason")).isEqualTo("DUE_REVIEW");
    }

    @Test
    @DisplayName("a review session with nothing due falls through to new material")
    void reviewSessionIsNeverEmpty() throws Exception {
        for (int i = 0; i < 3; i++) {
            createMcq("Unseen " + i);
        }

        MvcResult review = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"FLASHCARD_REVIEW","length":3}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        // An empty screen is a wasted intention. Building the backlog is the useful thing to do
        // when there is nothing to clear.
        assertThat(Json.readString(review.getResponse().getContentAsString(),
                "currentItem.selectionReason.reason")).isEqualTo("NEW_MATERIAL");
    }

    @Test
    @DisplayName("Again brings the card back in the same sitting")
    void lapsedCardComesBackInTheSameSitting() throws Exception {
        createFlashcard("Lapsed", "Forgotten just now");
        String sessionId = startSession("PRACTICE", 1);

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"position":0,"answer":{"grade":1},"responseTimeMs":9000}
                        """));
        mockMvc.perform(post(url("/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));

        // Ten minutes out, so strictly not due. The debt count says so honestly...
        mockMvc.perform(get(spaceUrl("/review-debt"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.due").value(0));

        // ...but a review session still reaches for it, because a card you have just failed is
        // the most valuable thing you could see again, and refusing it over nine minutes would
        // be the letter of the schedule defeating its purpose.
        MvcResult review = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"FLASHCARD_REVIEW","length":5}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(Json.readString(review.getResponse().getContentAsString(),
                "currentItem.selectionReason.reason")).isEqualTo("DUE_REVIEW");
    }

    @Test
    @DisplayName("an item scheduled for the future is not served early")
    void futureItemsAreNotServed() throws Exception {
        createFlashcard("Future", "Not yet");
        String sessionId = startSession("PRACTICE", 1);
        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"position":0,"answer":{"grade":4},"responseTimeMs":2000}
                        """));
        mockMvc.perform(post(url("/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));

        MvcResult review = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"FLASHCARD_REVIEW","length":5}
                                """))
                .andReturn();

        // Showing an item early is the one thing a spaced-repetition system exists to avoid.
        if (review.getResponse().getStatus() == 201) {
            assertThat(Json.readString(review.getResponse().getContentAsString(),
                    "currentItem.selectionReason.reason")).isNotEqualTo("DUE_REVIEW");
        } else {
            assertThat(Json.readString(review.getResponse().getContentAsString(), "code"))
                    .isEqualTo("session.no_questions");
        }
    }

    // ------------------------------------------------------------------ still derived

    @Test
    @DisplayName("the review schedule is rebuildable from the attempt log")
    void scheduleIsDerived() throws Exception {
        for (int i = 0; i < 4; i++) {
            createFlashcard("Rebuild " + i, "Back " + i);
        }

        // Two sittings, with time between them, so the spacing effect is actually exercised.
        for (int round = 0; round < 2; round++) {
            String sessionId = startSession("PRACTICE", 4);
            for (int i = 0; i < 4; i++) {
                mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":%d,"answer":{"grade":%d},"responseTimeMs":5000}
                                """.formatted(i, i == 0 ? 1 : 3)));
            }
            mockMvc.perform(post(url("/" + sessionId + "/complete"))
                    .header(HttpHeaders.AUTHORIZATION, user.bearer()));
            clock.advance(Duration.ofDays(3));
        }

        List<String> before = scheduleSnapshot();
        assertThat(before).isNotEmpty();

        mockMvc.perform(post(spaceUrl("/derived-state/rebuild"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scheduled").value(4));
        entityManager.clear();

        // If this ever fails, learning_states has become a source of truth, and changing the
        // scheduler later stops being a rebuild and becomes a migration that guesses.
        assertThat(scheduleSnapshot()).isEqualTo(before);
    }

    /* ------------------------------------------------------------------ helpers */

    private List<String> scheduleSnapshot() {
        List<String> rows = new ArrayList<>();
        for (LearningState state : states.findForLearner(
                UUID.fromString(user.id()), UUID.fromString(spaceId))) {
            rows.add("%s|%s|%.4f|%.2f|%d|%d|%s".formatted(
                    state.questionId(), state.phase(), state.stability(), state.difficulty(),
                    state.reps(), state.lapses(), state.dueAt()));
        }
        rows.sort(Comparator.naturalOrder());
        return rows;
    }

    private LearningState onlyState() {
        List<LearningState> all = states.findForLearner(
                UUID.fromString(user.id()), UUID.fromString(spaceId));
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private String spaceUrl(String suffix) {
        return "/api/v1/spaces/" + spaceId + suffix;
    }

    private String url(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/sessions" + suffix;
    }

    private String startSession(String mode, int length) throws Exception {
        MvcResult started = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"%s","length":%d}
                                """.formatted(mode, length)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(started.getResponse().getContentAsString(), "id");
    }

    private MvcResult answer(String sessionId, int position, boolean correct,
                             Integer responseTimeMs, Integer confidence) throws Exception {
        String extra = confidence == null ? "" : ",\"confidence\":" + confidence;
        return mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":%d,"answer":{"optionIds":["%s"]},
                                 "responseTimeMs":%d%s}
                                """.formatted(position, correct ? "a" : "b", responseTimeMs, extra)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String createMcq(String stem) throws Exception {
        return create("""
                {"subjectId":"%s","type":"MCQ","difficulty":"MEDIUM",
                 "stem":"%s","explanation":"Because that is how it works.",
                 "payload":{"options":[
                   {"id":"a","text":"right","correct":true},
                   {"id":"b","text":"wrong","correct":false}],"shuffle":false}}
                """.formatted(subjectId, stem));
    }

    private String createFlashcard(String front, String back) throws Exception {
        return create("""
                {"subjectId":"%s","type":"FLASHCARD","difficulty":"MEDIUM",
                 "stem":"%s","payload":{"front":"%s","back":"%s"}}
                """.formatted(subjectId, front, front, back));
    }

    private String create(String body) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(created.getResponse().getContentAsString(), "id");
    }

    private String createSpace(String typeKey, String name) throws Exception {
        MvcResult types = mockMvc.perform(get("/api/v1/preparation-types")
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String typeId = Json.<String>readList(types.getResponse().getContentAsString(),
                "[?(@.key=='" + typeKey + "')].id").get(0);

        MvcResult created = mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"%s"}
                                """.formatted(typeId, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(created.getResponse().getContentAsString(), "id");
    }

    private String createSubject(String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","weight":1}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(created.getResponse().getContentAsString(), "id");
    }
}
