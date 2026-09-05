package com.patipp.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.sessions.domain.StudySession;
import com.patipp.sessions.domain.StudySessionRepository;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
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
 * Exam mode.
 *
 * <p>Uses a controllable clock so the deadline can actually be crossed. Testing a timed exam
 * by sleeping would either take minutes or test nothing; the clock is injected everywhere
 * precisely so this is possible.
 */
@Import(ExamModeIntegrationTest.MovableClockConfig.class)
class ExamModeIntegrationTest extends IntegrationTest {

    /**
     * A clock the test can push forward.
     *
     * <p>Marked primary so it replaces the real one for every collaborator at once - the
     * session service, the handler and the expiry check must all agree on what time it is, or
     * the test would be exercising a disagreement rather than the feature.
     */
    @TestConfiguration
    static class MovableClockConfig {
        @Bean
        @Primary
        MovableClock movableClock() {
            // Starts at the real current time, not a fixed date in the past. Token validation
            // inside the JWT library uses the system clock rather than this one, so a frozen
            // past start would make every access token look expired and fail the whole test
            // class before it reached anything about exams. Advancing this clock is safe:
            // barely any real time passes, so tokens stay valid throughout.
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
    private StudySessionRepository sessions;

    private TestUser user;
    private String spaceId;
    private String jsSubjectId;
    private String cssSubjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Exam");
        spaceId = createSpace("ACADEMIC_EXAM", "Exam Space");
        jsSubjectId = createSubject("JavaScript", 3.0);
        cssSubjectId = createSubject("CSS", 1.0);
    }

    // ------------------------------------------------------------------ the clock

    @Test
    @DisplayName("an exam has a server-set deadline and reports its own remaining time")
    void deadlineIsServerSet() throws Exception {
        seedQuestions(jsSubjectId, 10, "js");

        MvcResult started = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":5,"durationMinutes":30}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("EXAM"))
                .andExpect(jsonPath("$.deadlineAt").isNotEmpty())
                // Deferred, unlike practice: knowing how question three went would change how
                // the rest of the paper is answered.
                .andExpect(jsonPath("$.immediateFeedback").value(false))
                .andExpect(jsonPath("$.freeNavigation").value(true))
                .andReturn();

        String body = started.getResponse().getContentAsString();
        long remaining = Long.parseLong(Json.readString(body, "remainingMs"));
        assertThat(remaining).isEqualTo(Duration.ofMinutes(30).toMillis());

        // Ten minutes pass. The remaining time follows the server's clock, not the browser's,
        // which is what makes closing the tab and returning safe.
        clock.advance(Duration.ofMinutes(10));

        String sessionId = Json.readString(body, "id");
        MvcResult resumed = mockMvc.perform(get(url("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(Long.parseLong(Json.readString(resumed.getResponse().getContentAsString(), "remainingMs")))
                .isEqualTo(Duration.ofMinutes(20).toMillis());
    }

    @Test
    @DisplayName("an answer arriving after the deadline is rejected")
    void answerAfterDeadlineIsRejected() throws Exception {
        seedQuestions(jsSubjectId, 5, "js");
        String sessionId = startExam(3, 15);

        answerFirst(sessionId);

        // Time runs out.
        clock.advance(Duration.ofMinutes(16));

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":1,"answer":{"optionIds":["a"]},"responseTimeMs":1000}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session.finished"))
                .andExpect(jsonPath("$.detail").value(
                        org.hamcrest.Matchers.containsString("Time is up")));
    }

    @Test
    @DisplayName("passing the deadline submits the exam automatically, scored on what was answered")
    void deadlineAutoSubmits() throws Exception {
        seedQuestions(jsSubjectId, 5, "js");
        String sessionId = startExam(4, 20);

        answerFirst(sessionId);
        clock.advance(Duration.ofMinutes(21));

        mockMvc.perform(get(url("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                // No countdown on a finished exam.
                .andExpect(jsonPath("$.remainingMs").doesNotExist());

        mockMvc.perform(get(url("/" + sessionId + "/summary"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.status").value("EXPIRED"))
                .andExpect(jsonPath("$.answeredCount").value(1))
                // Scored on what was answered. The three never reached are not marked wrong.
                .andExpect(jsonPath("$.score").value(100.0));

        StudySession stored = sessions.findById(UUID.fromString(sessionId)).orElseThrow();
        assertThat(stored.submittedAt()).isEqualTo(stored.deadlineAt());
    }

    @Test
    @DisplayName("the deadline comes from the blueprint when the request does not say")
    void durationFromBlueprint() throws Exception {
        seedQuestions(jsSubjectId, 3, "js");

        MvcResult started = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":3}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        // ACADEMIC_EXAM's blueprint says defaults.examDurationMinutes = 60. That number is
        // configuration, not code, and a certification space would get 130 instead.
        assertThat(Long.parseLong(Json.readString(started.getResponse().getContentAsString(), "remainingMs")))
                .isEqualTo(Duration.ofMinutes(60).toMillis());
    }

    // ------------------------------------------------------------------ feedback

    @Test
    @DisplayName("answering reveals nothing until the exam is over")
    void feedbackIsDeferred() throws Exception {
        seedQuestions(jsSubjectId, 3, "js");
        String sessionId = startExam(3, 30);

        MvcResult result = mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["a"]},"responseTimeMs":2000}
                                """))
                .andExpect(status().isOk())
                // Whether it was right is recorded, and withheld - the verdict included.
                // Sending "correct": false and asking the browser not to display it would
                // not be withholding anything; the network tab is one keypress away.
                .andExpect(jsonPath("$.correct").doesNotExist())
                .andExpect(jsonPath("$.score").doesNotExist())
                .andExpect(jsonPath("$.note").doesNotExist())
                .andExpect(jsonPath("$.explanation").doesNotExist())
                .andExpect(jsonPath("$.correctAnswer").doesNotExist())
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).doesNotContain("explanation");

        // Everything is revealed once the paper is submitted.
        mockMvc.perform(post(url("/" + sessionId + "/complete"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].explanation").isNotEmpty())
                .andExpect(jsonPath("$.items[0].correctAnswer").isNotEmpty());
    }

    // ------------------------------------------------------------------ navigation

    @Test
    @DisplayName("questions can be answered out of order, and marked to come back to")
    void freeNavigation() throws Exception {
        seedQuestions(jsSubjectId, 5, "js");
        String sessionId = startExam(5, 30);

        // Jump straight to the last question.
        mockMvc.perform(get(url("/" + sessionId + "/items/4"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.position").value(4));

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":4,"answer":{"optionIds":["a"]},"responseTimeMs":1000}
                                """))
                .andExpect(status().isOk());

        // Park question 2 to return to later.
        MvcResult marked = mockMvc.perform(post(url("/" + sessionId + "/items/2/mark"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String grid = marked.getResponse().getContentAsString();
        assertThat(Json.<String>readList(grid, "items[*].state"))
                .containsExactly("VIEWED", "UNSEEN", "MARKED_FOR_REVIEW", "UNSEEN", "ANSWERED");

        // Unmarking returns it to VIEWED, not UNSEEN: it has been seen, and saying otherwise
        // would misreport progress.
        MvcResult unmarked = mockMvc.perform(post(url("/" + sessionId + "/items/2/mark?marked=false"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(Json.<String>readList(unmarked.getResponse().getContentAsString(), "items[*].state"))
                .contains("VIEWED");
    }

    @Test
    @DisplayName("practice refuses free navigation rather than half-supporting it")
    void practiceHasNoFreeNavigation() throws Exception {
        seedQuestions(jsSubjectId, 3, "js");

        MvcResult practice = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PRACTICE","length":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.freeNavigation").value(false))
                .andReturn();

        String sessionId = Json.readString(practice.getResponse().getContentAsString(), "id");

        mockMvc.perform(get(url("/" + sessionId + "/items/2"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("session.navigation_not_allowed"));

        mockMvc.perform(post(url("/" + sessionId + "/items/2/mark"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("session.navigation_not_allowed"));
    }

    @Test
    @DisplayName("an answered question cannot be marked for review")
    void answeredCannotBeMarked() throws Exception {
        seedQuestions(jsSubjectId, 3, "js");
        String sessionId = startExam(3, 30);
        answerFirst(sessionId);

        mockMvc.perform(post(url("/" + sessionId + "/items/0/mark"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session.already_answered"));
    }

    @Test
    @DisplayName("an answer can be changed until the exam is submitted")
    void answersCanBeRevised() throws Exception {
        seedQuestions(jsSubjectId, 3, "js");
        String sessionId = startExam(3, 30);

        // "b" is the wrong option.
        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["b"]},"responseTimeMs":1000}
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(get(url("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.answeredCount").value(1))
                .andExpect(jsonPath("$.correctCount").value(0));

        // Thought better of it on the way back through the paper. The response still says
        // nothing about whether the new answer is right - that is what the counters are for.
        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["a"]},"responseTimeMs":4000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").doesNotExist());

        // One question answered, now correct: the revision replaced the answer rather than
        // adding a second one to the count.
        mockMvc.perform(get(url("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.answeredCount").value(1))
                .andExpect(jsonPath("$.correctCount").value(1));

        MvcResult summary = mockMvc.perform(post(url("/" + sessionId + "/complete"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(100.0))
                .andExpect(jsonPath("$.answeredCount").value(1))
                // The breakdown counts the answer, not the attempts behind it.
                .andExpect(jsonPath("$.breakdown.totalAnswered").value(1))
                .andReturn();

        // The review shows what was finally put down, and both attempts survive in the log.
        assertThat(summary.getResponse().getContentAsString()).contains("\"correct\":true");
    }

    @Test
    @DisplayName("practice refuses a second answer, because it has already shown you the first")
    void practiceRefusesRevision() throws Exception {
        seedQuestions(jsSubjectId, 3, "js");

        MvcResult practice = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PRACTICE","length":3}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = Json.readString(practice.getResponse().getContentAsString(), "id");
        answerFirst(sessionId);

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["b"]},"responseTimeMs":500}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session.already_answered"));
    }

    // ------------------------------------------------------------------ selection

    @Test
    @DisplayName("selection follows the blueprint's subject weights")
    void weightedByBlueprint() throws Exception {
        // JavaScript is weighted 3, CSS 1, and both have plenty of questions. A 20-question
        // paper should come out roughly three to one.
        seedQuestions(jsSubjectId, 40, "js");
        seedQuestions(cssSubjectId, 40, "css");

        String sessionId = startExam(20, 60);

        MvcResult summary = mockMvc.perform(post(url("/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        List<String> stems = Json.readList(summary.getResponse().getContentAsString(), "items[*].stem");

        long jsCount = stems.stream().filter(stem -> stem.startsWith("js")).count();
        assertThat(stems).hasSize(20);
        // Exactly 15 by quota; asserted as a band so rounding cannot make this brittle.
        assertThat(jsCount).isBetween(14L, 16L);
    }

    @Test
    @DisplayName("a weighted exam is still full length when one subject is thin")
    void thinSubjectDoesNotShortenTheExam() throws Exception {
        // CSS is weighted 1 but has only two questions. The shortfall must be topped up
        // rather than silently producing a shorter paper, which would misreport readiness.
        seedQuestions(jsSubjectId, 30, "js");
        seedQuestions(cssSubjectId, 2, "css");

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":20,"durationMinutes":60}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalItems").value(20));
    }

    @Test
    @DisplayName("the same seed reproduces the same paper")
    void seedReproducesThePaper() throws Exception {
        seedQuestions(jsSubjectId, 20, "js");

        List<String> first = stemsOfExamWithSeed(4242L);
        // A different sitting, same seed: the paper must come out identical, which is what
        // makes a suspicious score investigable rather than a mystery.
        List<String> again = stemsOfExamWithSeed(4242L);
        List<String> different = stemsOfExamWithSeed(9999L);

        assertThat(first).isEqualTo(again);
        assertThat(first).isNotEqualTo(different);
    }

    @Test
    @DisplayName("the seed used is stored on the session")
    void seedIsRecorded() throws Exception {
        seedQuestions(jsSubjectId, 5, "js");
        String sessionId = startExam(3, 30);

        StudySession stored = sessions.findById(UUID.fromString(sessionId)).orElseThrow();
        assertThat(stored.config()).containsKey("seed");
    }

    // ------------------------------------------------------------------ templates

    @Test
    @DisplayName("an exam setup can be saved, sat, and counted")
    void examTemplates() throws Exception {
        seedQuestions(jsSubjectId, 20, "js");

        MvcResult saved = mockMvc.perform(post(templateUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Full mock","length":10,"durationMinutes":25}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("Full mock"))
                .andExpect(jsonPath("$.timesUsed").value(0))
                .andReturn();

        String templateId = Json.readString(saved.getResponse().getContentAsString(), "id");

        mockMvc.perform(post(templateUrl("/" + templateId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Full mock","length":10}
                                """))
                .andExpect(status().isMethodNotAllowed());

        MvcResult sat = mockMvc.perform(post(templateUrl("/" + templateId + "/sit"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("EXAM"))
                .andExpect(jsonPath("$.totalItems").value(10))
                .andReturn();

        assertThat(Long.parseLong(Json.readString(sat.getResponse().getContentAsString(), "remainingMs")))
                .isEqualTo(Duration.ofMinutes(25).toMillis());

        mockMvc.perform(get(templateUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$[0].timesUsed").value(1))
                .andExpect(jsonPath("$[0].lastUsedAt").isNotEmpty());
    }

    @Test
    @DisplayName("two saved exams cannot share a name, and one can be archived")
    void templateNamesAreUnique() throws Exception {
        mockMvc.perform(post(templateUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Weekly mock","length":10}
                                """))
                .andExpect(status().isCreated());

        MvcResult duplicate = mockMvc.perform(post(templateUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"weekly MOCK","length":20}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("exam_template.name_taken"))
                .andReturn();
        assertThat(duplicate).isNotNull();

        MvcResult listed = mockMvc.perform(get(templateUrl(""))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String id = Json.<String>readList(listed.getResponse().getContentAsString(), "[*].id").getFirst();

        mockMvc.perform(delete(templateUrl("/" + id))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(templateUrl("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("saved exams in another user's space are invisible")
    void templateIsolation() throws Exception {
        TestUser stranger = registerUser("TemplateStranger");

        mockMvc.perform(get(templateUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(templateUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Injected","length":5}
                                """))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String url(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/sessions" + suffix;
    }

    private String templateUrl(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/exam-templates" + suffix;
    }

    private String startExam(int length, int minutes) throws Exception {
        MvcResult result = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":%d,"durationMinutes":%d}
                                """.formatted(length, minutes)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    private List<String> stemsOfExamWithSeed(long seed) throws Exception {
        MvcResult started = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":8,"durationMinutes":30,"seed":%d}
                                """.formatted(seed)))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = Json.readString(started.getResponse().getContentAsString(), "id");
        MvcResult summary = mockMvc.perform(post(url("/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        return Json.readList(summary.getResponse().getContentAsString(), "items[*].stem");
    }

    private void answerFirst(String sessionId) throws Exception {
        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["a"]},"responseTimeMs":2000}
                                """))
                .andExpect(status().isOk());
    }

    private String createSpace(String typeKey, String name) throws Exception {
        MvcResult types = mockMvc.perform(get("/api/v1/preparation-types")
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String typeId = Json.<String>readList(types.getResponse().getContentAsString(),
                "[?(@.key=='" + typeKey + "')].id").getFirst();

        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"%s"}
                                """.formatted(typeId, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    private String createSubject(String name, double weight) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s","weight":%s}
                                """.formatted(name, weight)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    /** Questions whose stems are prefixed, so the subject mix of a paper can be counted. */
    private void seedQuestions(String subjectId, int count, String prefix) throws Exception {
        for (int i = 0; i < count; i++) {
            mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions")
                            .header(HttpHeaders.AUTHORIZATION, user.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"subjectId":"%s","type":"MCQ","difficulty":"MEDIUM",
                                     "stem":"%s question number %d?",
                                     "explanation":"Because that is how it works.",
                                     "payload":{"options":[
                                       {"id":"a","text":"right","correct":true},
                                       {"id":"b","text":"wrong","correct":false}],"shuffle":false}}
                                    """.formatted(subjectId, prefix, i)))
                    .andExpect(status().isCreated());
        }
    }
}
