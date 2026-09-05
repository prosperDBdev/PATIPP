package com.patipp.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.adaptive.Elo;
import com.patipp.adaptive.MasteryLevel;
import com.patipp.learning.api.LearningAccess;
import com.patipp.learning.domain.TopicMastery;
import com.patipp.learning.domain.TopicMasteryRepository;
import com.patipp.questions.domain.QuestionStatsRepository;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import jakarta.persistence.EntityManager;
import java.util.ArrayList;
import java.util.Comparator;
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
 * The adaptive engine against a real database.
 *
 * <p>The engine's own rules are unit-tested in {@code com.patipp.adaptive} without Spring.
 * What is checked here is everything that only shows up once the two halves are joined: that
 * answering moves both Elo ratings, that the derived table is genuinely derived, and that a
 * practice session actually goes where the engine says it should.
 */
class AdaptiveLearningIntegrationTest extends IntegrationTest {

    @Autowired
    private TopicMasteryRepository mastery;

    @Autowired
    private QuestionStatsRepository stats;

    @Autowired
    private LearningAccess learning;

    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private String spaceId;
    private String jsSubjectId;
    private String cssSubjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Adaptive");
        spaceId = createSpace("ACADEMIC_EXAM", "Adaptive Space");
        jsSubjectId = createSubject("JavaScript");
        cssSubjectId = createSubject("CSS");
    }

    // ------------------------------------------------------------------ ability

    @Test
    @DisplayName("answering moves the learner's ability and the question's difficulty")
    void answeringMovesBothRatings() throws Exception {
        String questionId = createMcq(jsSubjectId, "Which keyword is block scoped?", "MEDIUM");
        String sessionId = startPractice(1);

        double ratingBefore = ratingOf(questionId);
        assertThat(ratingBefore).isEqualTo(Elo.seedFor("MEDIUM"));

        answer(sessionId, 0, true);

        List<TopicMastery> buckets = mastery.findForLearner(userId(), UUID.fromString(spaceId));
        assertThat(buckets).hasSize(1);

        TopicMastery bucket = buckets.get(0);
        assertThat(bucket.attempts()).isEqualTo(1);
        assertThat(bucket.correct()).isEqualTo(1);
        // A correct answer at even odds moves the learner up and the question down.
        assertThat(bucket.ability()).isGreaterThan(Elo.STARTING_RATING);
        assertThat(ratingOf(questionId)).isLessThan(ratingBefore);
    }

    @Test
    @DisplayName("untagged questions still accumulate mastery")
    void untaggedQuestionsHaveABucket() throws Exception {
        // No topic on the question. Without a bucket for these they would be invisible to
        // weakness detection, which is how a whole subject quietly stops being measured.
        createMcq(jsSubjectId, "An untagged question?", "MEDIUM");
        String sessionId = startPractice(1);
        answer(sessionId, 0, false);

        List<TopicMastery> buckets = mastery.findForLearner(userId(), UUID.fromString(spaceId));

        assertThat(buckets).hasSize(1);
        assertThat(buckets.get(0).topicId()).isNull();
        assertThat(buckets.get(0).subjectId()).isEqualTo(UUID.fromString(jsSubjectId));
    }

    @Test
    @DisplayName("mastery stays UNASSESSED until there is enough evidence to judge")
    void confidenceFloorIsRespected() throws Exception {
        for (int i = 0; i < 4; i++) {
            createMcq(jsSubjectId, "Question " + i + "?", "MEDIUM");
        }

        String sessionId = startPractice(4);
        for (int i = 0; i < 4; i++) {
            answer(sessionId, i, false);
        }

        TopicMastery bucket = mastery.findForLearner(userId(), UUID.fromString(spaceId)).get(0);

        // Four wrong answers is not evidence of weakness, it is four answers. Saying "you are
        // weak at JavaScript" here would be a study plan built on noise.
        assertThat(bucket.attempts()).isEqualTo(4);
        assertThat(bucket.masteryLevel()).isEqualTo(MasteryLevel.UNASSESSED);

        LearningAccess.Focus focus = learning.focusFor(userId(), UUID.fromString(spaceId));
        assertThat(focus.weakest()).isEmpty();
        assertThat(focus.needsAssessment()).hasSize(1);
        assertThat(focus.calibrating()).isTrue();
    }

    @Test
    @DisplayName("past the floor, a poor run is reported as weakness")
    void weaknessIsDetectedOnceMeasured() throws Exception {
        answerMany(jsSubjectId, "js", 8, false);

        TopicMastery bucket = mastery.findForLearner(userId(), UUID.fromString(spaceId)).get(0);

        assertThat(bucket.attempts()).isEqualTo(8);
        assertThat(bucket.masteryLevel()).isEqualTo(MasteryLevel.WEAK);
        assertThat(bucket.ability()).isLessThan(Elo.STARTING_RATING);

        LearningAccess.Focus focus = learning.focusFor(userId(), UUID.fromString(spaceId));
        assertThat(focus.weakest()).hasSize(1);
        assertThat(focus.weakest().get(0).level()).isEqualTo(MasteryLevel.WEAK);
    }

    @Test
    @DisplayName("a strong topic and a weak one are ranked in the right order")
    void weakestComesFirst() throws Exception {
        answerMany(jsSubjectId, "js", 8, true);
        answerMany(cssSubjectId, "css", 8, false);

        LearningAccess.Focus focus = learning.focusFor(userId(), UUID.fromString(spaceId));

        assertThat(focus.weakest()).hasSize(2);
        assertThat(focus.weakest().get(0).topic().subjectId())
                .isEqualTo(UUID.fromString(cssSubjectId));
        assertThat(focus.weakest().get(0).score())
                .isGreaterThan(focus.weakest().get(1).score());
    }

    // ------------------------------------------------------------------ the exit criterion

    @Test
    @DisplayName("rebuilding from the attempt log reproduces the incremental state exactly")
    void rebuildReproducesDerivedState() throws Exception {
        // The proof that the three-layer model holds. If this passes, topic_mastery and the
        // question ratings really are derived, the attempt log really is the only source of
        // truth, and the algorithm can be replaced later by rebuilding rather than by a
        // migration that guesses at history.
        answerMany(jsSubjectId, "js", 6, true);
        answerMany(cssSubjectId, "css", 6, false);
        answerMany(jsSubjectId, "js2", 4, false);

        List<Snapshot> before = snapshot();
        assertThat(before).isNotEmpty();

        learning.rebuild(userId(), UUID.fromString(spaceId));
        entityManager.clear();

        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    @DisplayName("a rebuild is idempotent")
    void rebuildTwiceIsTheSameAsOnce() throws Exception {
        answerMany(jsSubjectId, "js", 6, true);
        answerMany(cssSubjectId, "css", 5, false);

        learning.rebuild(userId(), UUID.fromString(spaceId));
        entityManager.clear();
        List<Snapshot> once = snapshot();

        learning.rebuild(userId(), UUID.fromString(spaceId));
        entityManager.clear();

        assertThat(snapshot()).isEqualTo(once);
    }

    @Test
    @DisplayName("the rebuild endpoint reports what it replayed")
    void rebuildEndpoint() throws Exception {
        answerMany(jsSubjectId, "js", 5, true);

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/derived-state/rebuild")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attemptsReplayed").value(5))
                .andExpect(jsonPath("$.buckets").value(1));
    }

    @Test
    @DisplayName("another user's space cannot be rebuilt")
    void rebuildIsScopedToTheOwner() throws Exception {
        TestUser other = registerUser("Intruder");

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/derived-state/rebuild")
                        .header(HttpHeaders.AUTHORIZATION, other.bearer()))
                // 404, not 403: whether this space exists is not the intruder's business.
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ selection

    @Test
    @DisplayName("a new space calibrates across the syllabus instead of guessing")
    void coldStartSpreadsAcrossSubjects() throws Exception {
        for (int i = 0; i < 10; i++) {
            createMcq(jsSubjectId, "js question " + i + "?", "MEDIUM");
            createMcq(cssSubjectId, "css question " + i + "?", "MEDIUM");
        }

        String sessionId = startPractice(8);
        MvcResult session = mockMvc.perform(get(sessionUrl("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        // Every item carries a reason from the first session onwards, so "why this question?"
        // stays answerable rather than being reconstructed later as a guess.
        assertThat(Json.readString(session.getResponse().getContentAsString(),
                "currentItem.selectionReason.reason")).isEqualTo("CALIBRATION");
    }

    @Test
    @DisplayName("once measured, practice goes to the weak subject")
    void practiceFollowsWeakness() throws Exception {
        // Enough history to be past calibration, strong in one subject and weak in the other.
        answerMany(jsSubjectId, "js", 8, true);
        answerMany(cssSubjectId, "css", 8, false);

        for (int i = 0; i < 20; i++) {
            createMcq(jsSubjectId, "js pool " + i + "?", "MEDIUM");
            createMcq(cssSubjectId, "css pool " + i + "?", "MEDIUM");
        }

        String sessionId = startPractice(10);
        MvcResult session = mockMvc.perform(get(sessionUrl("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String body = session.getResponse().getContentAsString();
        assertThat(Json.readString(body, "currentItem.selectionReason.reason"))
                .isNotEqualTo("CALIBRATION");
        assertThat(Json.readString(body, "currentItem.selectionReason.why"))
                .isNotBlank();
    }

    @Test
    @DisplayName("the answer key never appears in a selection reason")
    void selectionReasonRevealsNothing() throws Exception {
        answerMany(jsSubjectId, "js", 8, true);
        createMcq(jsSubjectId, "Which keyword is block scoped?", "MEDIUM");

        String sessionId = startPractice(1);
        MvcResult session = mockMvc.perform(get(sessionUrl("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andReturn();

        String reason = Json.readString(session.getResponse().getContentAsString(),
                "currentItem.selectionReason.reason");
        assertThat(reason).isNotBlank();
        // The reason explains the choice, never the answer.
        assertThat(session.getResponse().getContentAsString())
                .doesNotContain("\"correct\":true");
    }

    @Test
    @DisplayName("an exam still ignores the engine and samples the blueprint")
    void examDoesNotAdapt() throws Exception {
        answerMany(jsSubjectId, "js", 8, false);

        for (int i = 0; i < 20; i++) {
            createMcq(jsSubjectId, "js pool " + i + "?", "MEDIUM");
            createMcq(cssSubjectId, "css pool " + i + "?", "MEDIUM");
        }

        MvcResult exam = mockMvc.perform(post(sessionUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"EXAM","length":10,"durationMinutes":30}
                                """))
                .andExpect(status().isCreated())
                .andReturn();

        // A mock that adapted could not be compared with the one you sat a fortnight ago,
        // which is the only thing a mock is for.
        assertThat(Json.readString(exam.getResponse().getContentAsString(),
                "currentItem.selectionReason.reason")).isEqualTo("BLUEPRINT_WEIGHTED");
    }

    /* ------------------------------------------------------------------ helpers */

    /**
     * Every derived number, in a stable order.
     *
     * <p>Compared by value, so the rebuild has to reproduce the ratings themselves and not
     * merely the same number of rows.
     */
    private List<Snapshot> snapshot() {
        List<Snapshot> rows = new ArrayList<>();

        for (TopicMastery row : mastery.findForLearner(userId(), UUID.fromString(spaceId))) {
            rows.add(new Snapshot(
                    "mastery:" + row.subjectId() + ":" + row.topicId(),
                    // Rounded to two places, matching what the column stores: comparing raw
                    // doubles would fail on the last bit without anything being wrong.
                    Math.round(row.ability() * 100) / 100.0,
                    row.attempts(),
                    row.correct(),
                    row.questionsSeen(),
                    row.masteryLevel().name()));
        }

        // Every question in this test belongs to the one space, so no filter is needed.
        stats.findAll().stream()
                .forEach(row -> rows.add(new Snapshot(
                        "question:" + row.questionId(),
                        row.eloRating().doubleValue(),
                        row.ratingCount(), 0, 0, "")));

        rows.sort(Comparator.comparing(Snapshot::key));
        return rows;
    }

    private record Snapshot(String key, double rating, int a, int b, int c, String level) {
    }

    private double ratingOf(String questionId) {
        return stats.findById(UUID.fromString(questionId)).orElseThrow()
                .eloRating().doubleValue();
    }

    private UUID userId() {
        return UUID.fromString(user.id());
    }

    private String sessionUrl(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/sessions" + suffix;
    }

    /**
     * Creates {@code count} questions in one subject, sits them, and answers them all the
     * same way.
     *
     * <p>Filtered to that subject on purpose. Without the filter the adaptive engine would
     * quite correctly draw from everything in the space, including questions a previous call
     * already answered, and the history this builds would not be the history the test means
     * to describe.
     */
    private void answerMany(String subjectId, String prefix, int count, boolean correct)
            throws Exception {
        for (int i = 0; i < count; i++) {
            createMcq(subjectId, prefix + " item " + i + "?", "MEDIUM");
        }

        String sessionId = startPractice(count, subjectId);
        for (int i = 0; i < count; i++) {
            answer(sessionId, i, correct);
        }
        mockMvc.perform(post(sessionUrl("/" + sessionId + "/complete"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer()));
    }

    private String startPractice(int length) throws Exception {
        return startPractice(length, null);
    }

    private String startPractice(int length, String subjectId) throws Exception {
        String filter = subjectId == null ? "" : ",\"subjectIds\":[\"%s\"]".formatted(subjectId);

        MvcResult started = mockMvc.perform(post(sessionUrl(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"mode":"PRACTICE","length":%d%s}
                                """.formatted(length, filter)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(started.getResponse().getContentAsString(), "id");
    }

    private void answer(String sessionId, int position, boolean correct) throws Exception {
        mockMvc.perform(post(sessionUrl("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":%d,"answer":{"optionIds":["%s"]},"responseTimeMs":2000}
                                """.formatted(position, correct ? "a" : "b")))
                .andExpect(status().isOk());
    }

    private String createMcq(String subjectId, String stem, String difficulty) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","type":"MCQ","difficulty":"%s",
                                 "stem":"%s",
                                 "explanation":"Because that is how it works.",
                                 "payload":{"options":[
                                   {"id":"a","text":"right","correct":true},
                                   {"id":"b","text":"wrong","correct":false}],"shuffle":false}}
                                """.formatted(subjectId, difficulty, stem)))
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
