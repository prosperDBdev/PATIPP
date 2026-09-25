package com.patipp.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.attempts.domain.QuestionAttempt;
import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Coding practice end to end.
 *
 * <p>The exit criterion for this phase is a single claim: a self-graded coding attempt is
 * indistinguishable downstream from a multiple-choice one. Same attempt log, same Elo, same
 * mastery row, same analytics — differing only in the {@code evaluated_by} stamp that lets the
 * mix be audited. If that holds, hand-written practice has genuinely entered the system rather
 * than sitting beside it.
 */
class CodePracticeIntegrationTest extends IntegrationTest {

    @Autowired
    private QuestionAttemptRepository attempts;

    @Autowired
    private TopicMasteryRepository mastery;

    private TestUser user;
    private String spaceId;
    private String subjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Coder");
        spaceId = createSpace("CODING_TEST", "Interview prep");
        subjectId = createSubject("Algorithms");
    }

    @Test
    @DisplayName("a coding problem can be created, sat, and self-graded")
    void codingRoundTrip() throws Exception {
        String questionId = createCoding();

        // The learner never sees the solution before answering.
        String sessionId = startPractice(1);
        MvcResult session = mockMvc.perform(get(sessionUrl("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentItem.type").value("CODING"))
                .andExpect(jsonPath("$.currentItem.presentation.selfGraded").value(true))
                .andExpect(jsonPath("$.currentItem.presentation.rubricItemCount").value(2))
                .andExpect(jsonPath("$.currentItem.presentation.referenceSolution").doesNotExist())
                .andReturn();

        assertThat(session.getResponse().getContentAsString())
                .doesNotContain("Map<Integer, Integer>");

        // Solved it comfortably.
        mockMvc.perform(post(sessionUrl("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"grade":4},"responseTimeMs":840000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(true))
                // Everything is revealed now, which is the point of the reveal step.
                .andExpect(jsonPath("$.correctAnswer.referenceSolution").isNotEmpty())
                .andExpect(jsonPath("$.correctAnswer.rubric").isNotEmpty());

        QuestionAttempt attempt = onlyAttempt();
        assertThat(attempt.questionId()).isEqualTo(UUID.fromString(questionId));
        assertThat(attempt.isCorrect()).isTrue();
        // The stamp is the whole audit trail for self-assessment.
        assertThat(attempt.evaluatedBy()).isEqualTo("SELF");
        // And the 1-4 grade is recorded, which is what Phase 6 schedules from.
        assertThat(attempt.grade()).isEqualTo((short) 4);
    }

    @Test
    @DisplayName("a self-graded attempt moves mastery exactly like any other")
    void selfGradedAttemptFeedsTheEngine() throws Exception {
        createCoding();
        String sessionId = startPractice(1);

        mockMvc.perform(post(sessionUrl("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"grade":4},"responseTimeMs":600000}
                                """))
                .andExpect(status().isOk());

        // This is the phase's exit criterion: the adaptive engine cannot tell the difference.
        assertThat(mastery.findForLearner(UUID.fromString(user.id()), UUID.fromString(spaceId)))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.attempts()).isEqualTo(1);
                    assertThat(row.correct()).isEqualTo(1);
                    assertThat(row.ability()).isGreaterThan(1200.0);
                });
    }

    @Test
    @DisplayName("a debugging answer is graded half by the server and half by the learner")
    void debuggingIsMixed() throws Exception {
        createDebugging();
        String sessionId = startPractice(1);

        mockMvc.perform(get(sessionUrl("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.currentItem.presentation.lineCount").value(7))
                .andExpect(jsonPath("$.currentItem.presentation.defectLine").doesNotExist());

        // The right line, but honest about not being able to explain it.
        mockMvc.perform(post(sessionUrl("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"line":3,"grade":1},"responseTimeMs":90000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(false))
                .andExpect(jsonPath("$.score").value(0.5))
                .andExpect(jsonPath("$.note").value(
                        org.hamcrest.Matchers.containsString("Right line")));

        assertThat(onlyAttempt().evaluatedBy()).isEqualTo("MIXED");
    }

    @Test
    @DisplayName("output prediction is graded automatically, with nothing executed")
    void outputPredictionIsAutomatic() throws Exception {
        createOutputPrediction();
        String sessionId = startPractice(1);

        mockMvc.perform(post(sessionUrl("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"text":"2,4,6"},"responseTimeMs":45000}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correct").value(true));

        assertThat(onlyAttempt().evaluatedBy()).isEqualTo("AUTO");
        // No self-grade, so nothing for the scheduler to read directly - Phase 6 derives one.
        assertThat(onlyAttempt().grade()).isNull();
    }

    @Test
    @DisplayName("the expected output never reaches the browser before the answer")
    void expectedOutputIsWithheld() throws Exception {
        createOutputPrediction();
        String sessionId = startPractice(1);

        MvcResult session = mockMvc.perform(get(sessionUrl("/" + sessionId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        String body = session.getResponse().getContentAsString();
        assertThat(body).contains("xs.map");
        assertThat(body).doesNotContain("2,4,6");
    }

    @Test
    @DisplayName("indentation in a snippet survives the whole round trip")
    void indentationSurvives() throws Exception {
        createDebugging();
        String sessionId = startPractice(1);

        MvcResult session = mockMvc.perform(get(sessionUrl("/" + sessionId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        String served = Json.readString(session.getResponse().getContentAsString(),
                "currentItem.presentation.code");

        // Two spaces of indentation on the second line. A stripped snippet would silently
        // re-indent and a question about scoping would become unanswerable.
        assertThat(served.split("\n")[1]).startsWith("  let total");
    }

    @Test
    @DisplayName("a coding problem imports from JSON like any other format")
    void importsFromJson() throws Exception {
        String file = """
                [{"subject":"Algorithms","type":"CODING","difficulty":"HARD",
                  "stem":"Reverse a linked list in place.",
                  "payload":{"language":"java",
                    "referenceSolution":"Node prev = null; ...",
                    "rubric":["Iterative, not recursive","Handles a single node"]}}]
                """;

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions/import")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("JSON", file, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.invalid").value(0));
    }

    @Test
    @DisplayName("a coding problem without a rubric is refused at the door")
    void rubricIsRequiredOnCreate() throws Exception {
        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","type":"CODING","difficulty":"HARD",
                                 "stem":"Solve it.",
                                 "payload":{"referenceSolution":"x"}}
                                """.formatted(subjectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='rubric')]").isNotEmpty());
    }

    /* ------------------------------------------------------------------ helpers */

    private String importBody(String format, String content, boolean dryRun) {
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return """
                {"format":"%s","content":"%s","dryRun":%s}
                """.formatted(format, escaped, dryRun);
    }

    private QuestionAttempt onlyAttempt() {
        List<QuestionAttempt> all = attempts.findAllForLearner(
                UUID.fromString(user.id()), UUID.fromString(spaceId));
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private String sessionUrl(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/sessions" + suffix;
    }

    private String startPractice(int length) throws Exception {
        MvcResult started = mockMvc.perform(post(sessionUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PRACTICE","length":%d}
                                """.formatted(length)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(started.getResponse().getContentAsString(), "id");
    }

    private String createCoding() throws Exception {
        return create("""
                {"subjectId":"%s","type":"CODING","difficulty":"HARD",
                 "stem":"Given an array and a target, return the indices of two numbers that add to it.",
                 "payload":{"language":"java",
                   "referenceSolution":"Map<Integer, Integer> seen = new HashMap<>(); ...",
                   "complexity":"O(n) time, O(n) space",
                   "rubric":["Uses a hash map rather than two loops",
                             "Says what happens when there is no answer"]}}
                """);
    }

    private String createDebugging() throws Exception {
        return create("""
                {"subjectId":"%s","type":"DEBUGGING","difficulty":"MEDIUM",
                 "stem":"This should sum the array. What is wrong?",
                 "payload":{"language":"javascript",
                   "code":"function sum(xs) {\\n  let total = 0;\\n  for (let i = 0; i <= xs.length; i++) {\\n    total += xs[i];\\n  }\\n  return total;\\n}",
                   "defectLine":3,
                   "defectSummary":"The condition reads one index past the end of the array.",
                   "rubric":["Names the off-by-one"]}}
                """);
    }

    private String createOutputPrediction() throws Exception {
        return create("""
                {"subjectId":"%s","type":"OUTPUT_PREDICTION","difficulty":"EASY",
                 "stem":"What does this print?",
                 "payload":{"language":"javascript",
                   "code":"const xs = [1, 2, 3];\\nconsole.log(xs.map(x => x * 2).join(\\",\\"));",
                   "expectedOutput":"2,4,6"}}
                """);
    }

    private String create(String template) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(template.formatted(subjectId)))
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
