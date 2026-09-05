package com.patipp.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.attempts.domain.QuestionAttempt;
import com.patipp.attempts.domain.QuestionAttemptRepository;
import com.patipp.questions.domain.QuestionStatsRepository;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class PracticeSessionIntegrationTest extends IntegrationTest {

    @Autowired
    private QuestionAttemptRepository attempts;

    @Autowired
    private QuestionStatsRepository stats;

    @Autowired
    private EntityManager entityManager;

    private TestUser user;
    private String spaceId;
    private String subjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Practice");
        spaceId = createSpace("ACADEMIC_EXAM", "Practice Space");
        subjectId = createSubject("JavaScript");
    }

    // ------------------------------------------------------------------ the happy path

    @Test
    @DisplayName("a session serves questions, grades them, and reports a score")
    void fullPracticeRun() throws Exception {
        createMcq("Which keyword is block scoped?", "let");
        createTrueFalse("Closures capture their scope.", true);
        createShortAnswer("What runs async callbacks?", "event loop");

        MvcResult started = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"length":3}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("PRACTICE"))
                .andExpect(jsonPath("$.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.totalItems").value(3))
                .andExpect(jsonPath("$.immediateFeedback").value(true))
                // Practice has no deadline; a countdown changes how you answer.
                .andExpect(jsonPath("$.deadlineAt").doesNotExist())
                .andExpect(jsonPath("$.currentItem").isNotEmpty())
                .andReturn();

        String sessionId = Json.readString(started.getResponse().getContentAsString(), "id");

        for (int position = 0; position < 3; position++) {
            answerCorrectly(sessionId, position);
        }

        mockMvc.perform(post(url("/" + sessionId + "/complete"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.answeredCount").value(3))
                .andExpect(jsonPath("$.correctCount").value(3))
                .andExpect(jsonPath("$.score").value(100.0))
                .andExpect(jsonPath("$.items.length()").value(3));
    }

    @Test
    @DisplayName("every question format grades correctly, including partial credit")
    void allFormatsGrade() throws Exception {
        String mcq = createMcq("Pick let", "let");
        String tf = createTrueFalse("This is true.", true);
        String shortAnswer = createShortAnswer("Name the loop", "event loop");
        String multi = createMultiSelect("Pick the hooks");
        String card = createFlashcard("Closure", "A function plus its scope");
        assertThat(List.of(mcq, tf, shortAnswer, multi, card)).doesNotContainNull();

        String sessionId = startSession(5);

        // Walk the session, answering each item according to its type.
        for (int position = 0; position < 5; position++) {
            MvcResult state = mockMvc.perform(get(url("/" + sessionId))
                    .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
            String body = state.getResponse().getContentAsString();
            String type = Json.readString(body, "currentItem.type");
            int actualPosition = Json.readInt(body, "currentItem.position");

            String answer = switch (type) {
                case "MCQ" -> """
                        {"optionIds":["%s"]}""".formatted(correctOptionId(body));
                case "MULTI_SELECT" -> """
                        {"optionIds":["a"]}"""; // deliberately partial
                case "TRUE_FALSE" -> """
                        {"value":true}""";
                case "SHORT_ANSWER" -> """
                        {"text":"the Event Loop."}"""; // deliberately messy
                case "FLASHCARD" -> """
                        {"grade":3}""";
                default -> throw new IllegalStateException("unexpected type " + type);
            };

            MvcResult result = mockMvc.perform(post(url("/" + sessionId + "/answers"))
                            .header(HttpHeaders.AUTHORIZATION, user.bearer())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"position":%d,"answer":%s,"responseTimeMs":4000}
                                    """.formatted(actualPosition, answer)))
                    .andExpect(status().isOk())
                    .andReturn();

            String resultBody = result.getResponse().getContentAsString();

            if ("MULTI_SELECT".equals(type)) {
                // One of three correct: scored, but explicitly not counted as correct, which
                // is what stops partial answers inflating accuracy.
                assertThat(Json.readString(resultBody, "correct")).isEqualTo("false");
                assertThat(Double.parseDouble(Json.readString(resultBody, "score")))
                        .isGreaterThan(0.0).isLessThan(1.0);
            } else if ("SHORT_ANSWER".equals(type)) {
                // "the Event Loop." matches "event loop": case, punctuation and a leading
                // article are forgiven, because none of them is the knowledge being tested.
                assertThat(Json.readString(resultBody, "correct")).isEqualTo("true");
            } else {
                assertThat(Json.readString(resultBody, "correct")).isEqualTo("true");
            }
        }
    }

    // ------------------------------------------------------------------ the attempt log

    @Test
    @DisplayName("attempts are immutable: the database refuses an update")
    void attemptsCannotBeUpdated() throws Exception {
        createMcq("Immutable question", "let");
        String sessionId = startSession(1);
        answerCorrectly(sessionId, 0);

        List<QuestionAttempt> recorded = attempts.findAll();
        assertThat(recorded).hasSize(1);

        // Hibernate marks the entity @Immutable so it never issues an UPDATE of its own.
        // The guarantee that matters is the trigger, so go around Hibernate to prove it.
        assertThatThrownBy(() -> {
            entityManager.createNativeQuery(
                            "update question_attempts set is_correct = false where id = :id")
                    .setParameter("id", recorded.getFirst().id())
                    .executeUpdate();
            entityManager.flush();
        }).hasMessageContaining("append-only");
    }

    @Test
    @DisplayName("an attempt records the version served, not whatever the question says later")
    void attemptPinsTheVersion() throws Exception {
        String questionId = createMcq("Original wording", "let");
        String sessionId = startSession(1);
        answerCorrectly(sessionId, 0);

        UUID versionAtAnswerTime = attempts.findAll().getFirst().questionVersionId();

        // Edit the question after the fact, creating version 2.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/v1/spaces/" + spaceId + "/questions/" + questionId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stem":"Corrected wording"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));

        // The attempt still points at what was actually on screen when it was answered.
        assertThat(attempts.findAll().getFirst().questionVersionId()).isEqualTo(versionAtAnswerTime);
    }

    @Test
    @DisplayName("attempt numbers count how often this learner has seen the question")
    void attemptNumbersIncrement() throws Exception {
        createMcq("Repeated question", "let");

        for (int run = 1; run <= 3; run++) {
            String sessionId = startSession(1);
            answerCorrectly(sessionId, 0);
            mockMvc.perform(post(url("/" + sessionId + "/complete"))
                    .header(HttpHeaders.AUTHORIZATION, user.bearer()));
        }

        assertThat(attempts.findAll()).extracting(QuestionAttempt::attemptNo)
                .containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("attempts denormalise subject, topic and difficulty for later analytics")
    void attemptsDenormaliseForAnalytics() throws Exception {
        createMcq("Denormalised", "let");
        String sessionId = startSession(1);
        answerCorrectly(sessionId, 0);

        QuestionAttempt attempt = attempts.findAll().getFirst();

        // Copied at answer time so analytics never joins, and so moving the question to
        // another topic later cannot rewrite what was practised today.
        assertThat(attempt.subjectId()).isEqualTo(UUID.fromString(subjectId));
        assertThat(attempt.difficulty()).isEqualTo("MEDIUM");
        assertThat(attempt.mode()).isEqualTo("PRACTICE");
        assertThat(attempt.responseTimeMs()).isEqualTo(4000);
    }

    @Test
    @DisplayName("answering updates the question's aggregate statistics")
    void questionStatsAccumulate() throws Exception {
        String questionId = createMcq("Counted question", "let");
        String sessionId = startSession(1);
        answerCorrectly(sessionId, 0);

        var row = stats.findById(UUID.fromString(questionId)).orElseThrow();
        assertThat(row.timesServed()).isEqualTo(1);
        assertThat(row.timesCorrect()).isEqualTo(1);
        assertThat(row.avgResponseMs()).isEqualTo(4000);
    }

    // ------------------------------------------------------------------ rules

    @Test
    @DisplayName("the served question never contains the answer key")
    void answerKeyNeverReachesTheClient() throws Exception {
        createMcq("Which keyword is block scoped?", "let");
        String sessionId = startSession(1);

        MvcResult state = mockMvc.perform(get(url("/" + sessionId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String body = state.getResponse().getContentAsString();

        // Options are present; which one is right is not. Hiding it in the UI instead would
        // put the answers in the page source.
        assertThat(Json.readInt(body, "currentItem.presentation.options.length()")).isEqualTo(3);
        assertThat(body).doesNotContain("\"correct\"");
        // The question's own explanation, specifically. The selection reason carries a "why"
        // of its own, which explains the choice rather than the answer.
        assertThat(body).doesNotContain("\"explanation\"");
    }

    @Test
    @DisplayName("a question cannot be answered twice")
    void cannotAnswerTwice() throws Exception {
        createMcq("Answer once", "let");
        String sessionId = startSession(1);
        answerCorrectly(sessionId, 0);

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["a"]}}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session.already_answered"));
    }

    @Test
    @DisplayName("only one session may be open in a space at a time")
    void oneSessionAtATime() throws Exception {
        createMcq("First", "let");
        createMcq("Second", "const");
        startSession(1);

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"length":1}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("session.already_in_progress"));
    }

    @Test
    @DisplayName("starting with no matching questions is refused with a useful message")
    void noQuestionsToPractise() throws Exception {
        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"length":5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("session.no_questions"));
    }

    @Test
    @DisplayName("a session shorter than requested is served rather than refused")
    void shortSessionRatherThanError() throws Exception {
        createMcq("Only one available", "let");

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"length":20}
                                """))
                .andExpect(status().isCreated())
                // Asking for twenty and having one is not an error; it is a short session.
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    @DisplayName("session length falls back to the preparation type's default")
    void lengthFromBlueprint() throws Exception {
        for (int i = 0; i < 25; i++) {
            createMcq("Filler question " + i, "let");
        }

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                // ACADEMIC_EXAM blueprint says defaults.sessionLength = 20. That number is
                // configuration, not code.
                .andExpect(jsonPath("$.totalItems").value(20));
    }

    @Test
    @DisplayName("filters narrow what is served")
    void filtersApply() throws Exception {
        createMcq("An MCQ", "let");
        createFlashcard("A card", "The back");

        MvcResult availability = mockMvc.perform(post(url("/availability"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"types":["FLASHCARD"]}
                                """))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(Json.readInt(availability.getResponse().getContentAsString(), "availableQuestions"))
                .isEqualTo(1);

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"types":["FLASHCARD"],"length":5}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.currentItem.type").value("FLASHCARD"));
    }

    @Test
    @DisplayName("an unfinished session is resumable, and reports where it left off")
    void sessionIsResumable() throws Exception {
        createMcq("First", "let");
        createMcq("Second", "const");
        String sessionId = startSession(2);
        answerCorrectly(sessionId, 0);

        mockMvc.perform(get(url("/in-progress")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(sessionId))
                .andExpect(jsonPath("$.answeredCount").value(1));

        mockMvc.perform(get(url("/" + sessionId)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.currentItem.position").value(1));
    }

    @Test
    @DisplayName("abandoning keeps the answers already given")
    void abandonKeepsAttempts() throws Exception {
        createMcq("First", "let");
        createMcq("Second", "const");
        String sessionId = startSession(2);
        answerCorrectly(sessionId, 0);

        mockMvc.perform(post(url("/" + sessionId + "/abandon"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        // The sitting is discarded; the answer is not. It is still evidence of what was
        // practised, and Phase 5 will count it.
        assertThat(attempts.findAll()).hasSize(1);

        // The session is marked abandoned rather than submitted: it says honestly that this
        // sitting was not finished, while the answer inside it still counts.
        mockMvc.perform(get(url("/" + sessionId + "/summary"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.status").value("ABANDONED"))
                .andExpect(jsonPath("$.answeredCount").value(1));
    }

    @Test
    @DisplayName("the summary breaks results down by difficulty and topic")
    void summaryHasBreakdown() throws Exception {
        createMcq("Graded question", "let");
        String sessionId = startSession(1);
        answerCorrectly(sessionId, 0);

        MvcResult summary = mockMvc.perform(post(url("/" + sessionId + "/complete"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String body = summary.getResponse().getContentAsString();
        assertThat(Json.readInt(body, "breakdown.byDifficulty.MEDIUM.answered")).isEqualTo(1);
        assertThat(Json.readInt(body, "breakdown.byDifficulty.MEDIUM.correct")).isEqualTo(1);
        // Everything is revealed in the review, including the explanation.
        assertThat(Json.readString(body, "items[0].explanation")).isNotBlank();
        // CALIBRATION, not RANDOM: from Phase 5 practice selection is adaptive, and a space
        // with almost no history spreads across the syllabus rather than pretending to know
        // where the learner is weak. Either way the reason is recorded, which is the point.
        assertThat(Json.readString(body, "items[0].selectionReason.reason"))
                .isEqualTo("CALIBRATION");
    }

    @Test
    @DisplayName("submitting early scores only what was answered")
    void earlySubmitDoesNotPunish() throws Exception {
        createMcq("First", "let");
        createMcq("Second", "const");
        createMcq("Third", "var");
        String sessionId = startSession(3);
        answerCorrectly(sessionId, 0);

        mockMvc.perform(post(url("/" + sessionId + "/complete"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answeredCount").value(1))
                // 1 of 1 answered correctly, not 1 of 3. Scoring the unanswered as wrong
                // would punish stopping, which practice should not discourage.
                .andExpect(jsonPath("$.score").value(100.0));
    }

    @Test
    @DisplayName("an answer of the wrong shape is a 400, not a server error")
    void malformedAnswerRejected() throws Exception {
        createTrueFalse("A statement.", true);
        String sessionId = startSession(1);

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":0,"answer":{"optionIds":["a"]}}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("question.content_invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("value"));
    }

    @Test
    @DisplayName("a session in another user's space is invisible")
    void isolation() throws Exception {
        createMcq("Private", "let");
        String sessionId = startSession(1);
        TestUser stranger = registerUser("SessionStranger");

        mockMvc.perform(get(url("/" + sessionId))
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String url(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/sessions" + suffix;
    }

    private String startSession(int length) throws Exception {
        MvcResult result = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"length":%d}
                                """.formatted(length)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    /** Answers the item at a position with whatever its format considers right. */
    private void answerCorrectly(String sessionId, int position) throws Exception {
        MvcResult state = mockMvc.perform(get(url("/" + sessionId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String body = state.getResponse().getContentAsString();
        String type = Json.readString(body, "currentItem.type");

        String answer = switch (type) {
            case "MCQ", "MULTI_SELECT" -> """
                    {"optionIds":["%s"]}""".formatted(correctOptionId(body));
            case "TRUE_FALSE" -> """
                    {"value":true}""";
            case "SHORT_ANSWER" -> """
                    {"text":"event loop"}""";
            case "FLASHCARD" -> """
                    {"grade":4}""";
            default -> throw new IllegalStateException("unexpected type " + type);
        };

        mockMvc.perform(post(url("/" + sessionId + "/answers"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"position":%d,"answer":%s,"responseTimeMs":4000}
                                """.formatted(position, answer)))
                .andExpect(status().isOk());
    }

    /**
     * The correct option is not sent to the client, so the test finds it by the text it
     * created the question with. That is the point: even the test cannot read it off the wire.
     */
    private String correctOptionId(String sessionBody) {
        List<String> ids = Json.readList(sessionBody, "currentItem.presentation.options[*].id");
        List<String> texts = Json.readList(sessionBody, "currentItem.presentation.options[*].text");
        for (int i = 0; i < texts.size(); i++) {
            if (texts.get(i).equals("let") || texts.get(i).equals("useState")) {
                return ids.get(i);
            }
        }
        return ids.getFirst();
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

    private String createSubject(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"%s"}
                                """.formatted(name)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    private String createQuestion(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/questions")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    private String createMcq(String stem, String correctText) throws Exception {
        return createQuestion("""
                {"subjectId":"%s","type":"MCQ","difficulty":"MEDIUM","stem":"%s",
                 "explanation":"Because that is how it works.",
                 "payload":{"options":[
                   {"id":"a","text":"%s","correct":true},
                   {"id":"b","text":"another","correct":false},
                   {"id":"c","text":"a third","correct":false}],"shuffle":false}}
                """.formatted(subjectId, stem, correctText));
    }

    private String createMultiSelect(String stem) throws Exception {
        return createQuestion("""
                {"subjectId":"%s","type":"MULTI_SELECT","difficulty":"MEDIUM","stem":"%s",
                 "explanation":"Three of these are hooks.",
                 "payload":{"options":[
                   {"id":"a","text":"useState","correct":true},
                   {"id":"b","text":"useEffect","correct":true},
                   {"id":"c","text":"useDatabase","correct":false},
                   {"id":"d","text":"useMemo","correct":true}],
                  "partialCredit":true,"shuffle":false}}
                """.formatted(subjectId, stem));
    }

    private String createTrueFalse(String stem, boolean answer) throws Exception {
        return createQuestion("""
                {"subjectId":"%s","type":"TRUE_FALSE","difficulty":"MEDIUM","stem":"%s",
                 "explanation":"That is correct.","payload":{"answer":%s}}
                """.formatted(subjectId, stem, answer));
    }

    private String createShortAnswer(String stem, String accepted) throws Exception {
        return createQuestion("""
                {"subjectId":"%s","type":"SHORT_ANSWER","difficulty":"MEDIUM","stem":"%s",
                 "explanation":"The event loop does.",
                 "payload":{"acceptedAnswers":["%s"],"matchMode":"NORMALIZED"}}
                """.formatted(subjectId, stem, accepted));
    }

    private String createFlashcard(String front, String back) throws Exception {
        return createQuestion("""
                {"subjectId":"%s","type":"FLASHCARD","difficulty":"MEDIUM","stem":"%s",
                 "payload":{"front":"%s","back":"%s"}}
                """.formatted(subjectId, front, front, back));
    }
}
