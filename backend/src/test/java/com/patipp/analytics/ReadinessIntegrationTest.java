package com.patipp.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.analytics.domain.ReadinessSnapshotRepository;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Readiness against a real database.
 *
 * <p>The model's own judgements are unit-tested without Spring. What is checked here is that the
 * inputs are gathered correctly from four different modules, that the snapshot is written once per
 * day rather than once per request, and that the score is genuinely explainable — recomputable by
 * hand from what the response carries.
 */
class ReadinessIntegrationTest extends IntegrationTest {

    @Autowired
    private ReadinessSnapshotRepository snapshots;

    private TestUser user;
    private String spaceId;
    private String subjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Analyst");
        spaceId = createSpace("ACADEMIC_EXAM", "Readiness space");
        subjectId = createSubject("JavaScript");
    }

    @Test
    @DisplayName("a brand-new space reports calibrating rather than a number")
    void newSpaceIsCalibrating() throws Exception {
        mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.score").value(0.0))
                .andExpect(jsonPath("$.confidenceBand").value("CALIBRATING"))
                // Said in words, not left as a bare zero for the learner to interpret.
                .andExpect(jsonPath("$.headline").value(
                        org.hamcrest.Matchers.containsString("calibrating")));
    }

    @Test
    @DisplayName("the response carries its own breakdown, and the score can be checked by hand")
    void scoreIsRecomputable() throws Exception {
        answerSome(20, true);

        MvcResult result = mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.coverage").exists())
                .andExpect(jsonPath("$.components.accuracy").exists())
                .andExpect(jsonPath("$.components.depth").exists())
                .andExpect(jsonPath("$.components.retention").exists())
                .andExpect(jsonPath("$.components.consistency").exists())
                .andExpect(jsonPath("$.components.mock").exists())
                .andExpect(jsonPath("$.modelVersion").value("WEIGHTED_V1"))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Six weights summing to one, and score = sum(component * weight) * confidence. If this
        // drifts, the stored breakdown is decoration rather than an explanation.
        double weightSum = 0;
        double recomputed = 0;
        for (String component : List.of("coverage", "accuracy", "depth", "retention",
                "consistency", "mock")) {
            double value = Double.parseDouble(Json.readString(body, "components." + component));
            double weight = Double.parseDouble(Json.readString(body, "weights." + component));
            weightSum += weight;
            recomputed += value * weight;
        }

        assertThat(weightSum).isCloseTo(1.0, org.assertj.core.data.Offset.offset(0.01));
        double confidence = Double.parseDouble(Json.readString(body, "confidence"));
        double reported = Double.parseDouble(Json.readString(body, "score"));
        assertThat(recomputed * confidence)
                .isCloseTo(reported, org.assertj.core.data.Offset.offset(0.5));
    }

    @Test
    @DisplayName("an early score is capped, and says why")
    void earlyScoreIsCapped() throws Exception {
        answerSome(20, true);

        MvcResult result = mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.confidenceBand").value("LOW"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        double raw = Double.parseDouble(Json.readString(body, "raw"));
        double score = Double.parseDouble(Json.readString(body, "score"));

        // The gap between the two IS the explanation, which is why both are sent.
        assertThat(score).isLessThan(raw);
        assertThat(Double.parseDouble(Json.readString(body, "confidence"))).isLessThan(1.0);
    }

    @Test
    @DisplayName("readiness is not the average quiz percentage")
    void notJustAccuracy() throws Exception {
        // Twenty answers, all correct, in one topic of one subject. Nothing reviewed, no mock
        // sat, one day of study. A quiz average would call this 100%.
        answerSome(20, true);

        MvcResult result = mockMvc.perform(get(url("/readiness"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(Double.parseDouble(Json.readString(body, "components.accuracy")))
                .isGreaterThan(80.0);
        // The entire justification for six components rather than one.
        assertThat(Double.parseDouble(Json.readString(body, "score"))).isLessThan(55.0);
        assertThat(Double.parseDouble(Json.readString(body, "components.mock"))).isZero();
    }

    @Test
    @DisplayName("it says what to do next, and what that is worth")
    void biggestLeverIsReported() throws Exception {
        answerSome(20, true);

        mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.biggestLever.component").isNotEmpty())
                .andExpect(jsonPath("$.biggestLever.action").isNotEmpty())
                .andExpect(jsonPath("$.biggestLever.estimatedGain").exists());
    }

    @Test
    @DisplayName("repeated reads rewrite today's snapshot rather than appending")
    void snapshotIsOncePerDay() throws Exception {
        answerSome(10, true);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get(url("/readiness"))
                            .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                    .andExpect(status().isOk());
        }

        // A readiness figure that moved four times before lunch is noise; the useful question is
        // how today compares with the last measurement.
        assertThat(snapshots.findAll()).hasSize(1);

        mockMvc.perform(get(url("/readiness/history"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].components.accuracy").exists());
    }

    @Test
    @DisplayName("answering builds the activity record and the streak")
    void activityIsRecorded() throws Exception {
        answerSome(6, true);

        mockMvc.perform(get(url("/activity"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days.length()").value(1))
                .andExpect(jsonPath("$.days[0].answered").value(6))
                .andExpect(jsonPath("$.days[0].correct").value(6))
                .andExpect(jsonPath("$.streak.current").value(1))
                .andExpect(jsonPath("$.streak.answeredToday").value(true));
    }

    @Test
    @DisplayName("a mock exam moves the mock component, which practice cannot")
    void mockComponentNeedsAnExam() throws Exception {
        answerSome(20, true);

        double withoutMock = Double.parseDouble(Json.readString(
                mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                        .andReturn().getResponse().getContentAsString(),
                "components.mock"));
        assertThat(withoutMock).isZero();

        sitAnExam();

        double withMock = Double.parseDouble(Json.readString(
                mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                        .andReturn().getResponse().getContentAsString(),
                "components.mock"));

        // Nothing but an exam tells you how it holds up under pressure, which is why practice
        // cannot move this component however much of it you do.
        assertThat(withMock).isGreaterThan(0.0);
    }

    @Test
    @DisplayName("readiness in another user's space reports absence, not refusal")
    void isolationLooksLikeAbsence() throws Exception {
        TestUser other = registerUser("Intruder");

        mockMvc.perform(get(url("/readiness"))
                        .header(HttpHeaders.AUTHORIZATION, other.bearer()))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(url("/activity"))
                        .header(HttpHeaders.AUTHORIZATION, other.bearer()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the activity counts survive a rebuild from the attempt log")
    void activityIsDerived() throws Exception {
        answerSome(8, true);

        MvcResult before = mockMvc.perform(get(url("/activity"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        mockMvc.perform(post(url("/derived-state/rebuild"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk());

        MvcResult after = mockMvc.perform(get(url("/activity"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        assertThat(Json.readString(after.getResponse().getContentAsString(), "days[0].answered"))
                .isEqualTo(Json.readString(before.getResponse().getContentAsString(),
                        "days[0].answered"));
    }

    /* ------------------------------------------------------------------ helpers */

    private String url(String suffix) {
        return "/api/v1/spaces/" + spaceId + suffix;
    }

    private void answerSome(int count, boolean correct) throws Exception {
        for (int i = 0; i < count; i++) {
            createMcq("Question " + i + "?");
        }

        MvcResult started = mockMvc.perform(post(url("/sessions"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PRACTICE","length":%d}
                                """.formatted(count)))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = Json.readString(started.getResponse().getContentAsString(), "id");
        for (int i = 0; i < count; i++) {
            mockMvc.perform(post(url("/sessions/" + sessionId + "/answers"))
                            .header(HttpHeaders.AUTHORIZATION, user.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"position":%d,"answer":{"optionIds":["%s"]},
                                     "responseTimeMs":30000}
                                    """.formatted(i, correct ? "a" : "b")))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(post(url("/sessions/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));
    }

    private void sitAnExam() throws Exception {
        MvcResult started = mockMvc.perform(post(url("/sessions"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":5,"durationMinutes":30}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        String sessionId = Json.readString(started.getResponse().getContentAsString(), "id");
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post(url("/sessions/" + sessionId + "/answers"))
                    .header(HttpHeaders.AUTHORIZATION, user.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"position":%d,"answer":{"optionIds":["a"]},"responseTimeMs":20000}
                            """.formatted(i)));
        }
        mockMvc.perform(post(url("/sessions/" + sessionId + "/complete"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk());
    }

    private void createMcq(String stem) throws Exception {
        mockMvc.perform(post(url("/questions"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","type":"MCQ","difficulty":"MEDIUM",
                                 "stem":"%s","explanation":"Because that is how it works.",
                                 "payload":{"options":[
                                   {"id":"a","text":"right","correct":true},
                                   {"id":"b","text":"wrong","correct":false}],"shuffle":false}}
                                """.formatted(subjectId, stem)))
                .andExpect(status().isCreated());
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
        MvcResult created = mockMvc.perform(post(url("/subjects"))
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
