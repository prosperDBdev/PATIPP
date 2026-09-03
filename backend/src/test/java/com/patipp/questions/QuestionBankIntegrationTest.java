package com.patipp.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class QuestionBankIntegrationTest extends IntegrationTest {

    private TestUser user;
    private String spaceId;
    private String subjectId;
    private String topicId;

    @BeforeEach
    void setUpSpace() throws Exception {
        user = registerUser("Bank");
        spaceId = createSpace(user, "ACADEMIC_EXAM", "Question Bank Space");
        subjectId = createSubject("JavaScript");
        topicId = createTopic(subjectId, "Closures");
    }

    @Test
    @DisplayName("a multiple-choice question is created and read back with its payload")
    void createAndRead() throws Exception {
        String id = createMcq("Which keyword declares a block-scoped constant?", "const");

        mockMvc.perform(get(url("/" + id)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type").value("MCQ"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.difficulty").value("MEDIUM"))
                .andExpect(jsonPath("$.source").value("MANUAL"))
                .andExpect(jsonPath("$.payload.options.length()").value(3))
                // Not supplied, so derived from the question type rather than left at zero.
                .andExpect(jsonPath("$.estimatedSeconds").value(60));
    }

    @Test
    @DisplayName("content that breaks the rules of its type is rejected with field errors")
    void invalidContentIsRejected() throws Exception {
        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","type":"MCQ","difficulty":"MEDIUM",
                                 "stem":"Two right answers?",
                                 "payload":{"options":[
                                   {"id":"a","text":"one","correct":true},
                                   {"id":"b","text":"two","correct":true}]}}
                                """.formatted(subjectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("question.content_invalid"))
                .andExpect(jsonPath("$.errors[0].field").value("options"))
                .andExpect(jsonPath("$.errors[0].message").value(
                        org.hamcrest.Matchers.containsString("exactly one")));
    }

    @Test
    @DisplayName("the same question cannot be added twice, ignoring case and punctuation")
    void duplicatesAreRejected() throws Exception {
        createMcq("What does const do?", "Declares a constant");

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mcqBody("  what does CONST do  ", "Declares a constant")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("question.duplicate"));
    }

    @Test
    @DisplayName("editing the wording creates a new version and keeps the old one")
    void editingCreatesAVersion() throws Exception {
        String id = createMcq("Whta is a closure?", "A function plus its scope");

        mockMvc.perform(patch(url("/" + id))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stem":"What is a closure?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andExpect(jsonPath("$.stem").value("What is a closure?"));

        MvcResult history = mockMvc.perform(get(url("/" + id + "/versions"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String body = history.getResponse().getContentAsString();
        // The typo survives in version 1. An attempt recorded before the fix still points at
        // the exact text it was answered against.
        assertThat(Json.<String>readList(body, "[*].stem"))
                .containsExactly("What is a closure?", "Whta is a closure?");
        assertThat(Json.<Boolean>readList(body, "[*].current")).containsExactly(true, false);
    }

    @Test
    @DisplayName("changing only metadata does not create a new version")
    void metadataEditsDoNotVersion() throws Exception {
        String id = createMcq("Which is block scoped?", "let");

        mockMvc.perform(patch(url("/" + id))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"difficulty":"HARD","tags":["Scope","SCOPE","hoisting"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.difficulty").value("HARD"))
                // Tags are lower-cased and de-duplicated: a filter where React and react are
                // different values hides half the bank.
                .andExpect(jsonPath("$.tags.length()").value(2))
                .andExpect(jsonPath("$.tags[0]").value("scope"));

        mockMvc.perform(get(url("/" + id + "/versions"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    @DisplayName("a question can be archived and restored")
    void archiveAndRestore() throws Exception {
        String id = createMcq("Temporary question?", "yes");

        mockMvc.perform(delete(url("/" + id)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(0));

        mockMvc.perform(get(url("/" + id)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(url("/" + id + "/restore"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    @DisplayName("archiving frees the wording for reuse, and restoring then conflicts")
    void archiveFreesTheDuplicateSlot() throws Exception {
        String original = createMcq("Reusable wording?", "yes");

        mockMvc.perform(delete(url("/" + original)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        // The unique index covers live rows only, so the wording is available again.
        createMcq("Reusable wording?", "yes");

        // Restoring the archived one would now collide. Say so rather than failing on a
        // constraint the user cannot see.
        mockMvc.perform(post(url("/" + original + "/restore"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("question.duplicate"));
    }

    @Test
    @DisplayName("the list filters by subject, type, difficulty and text")
    void filtering() throws Exception {
        createMcq("Filter me by type", "yes");
        createFlashcard("Flashcard front", "Flashcard back");

        mockMvc.perform(get(url("?type=MCQ")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].type").value("MCQ"));

        mockMvc.perform(get(url("?type=FLASHCARD")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get(url("?search=flashcard")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(1));

        mockMvc.perform(get(url("?subjectId=" + subjectId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(2));

        mockMvc.perform(get(url("?difficulty=EXPERT")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    @DisplayName("a topic decides the subject, so the two can never disagree")
    void topicWinsOverSubject() throws Exception {
        String otherSubject = createSubject("CSS");

        MvcResult result = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","topicId":"%s","type":"TRUE_FALSE",
                                 "difficulty":"EASY","stem":"Closures capture scope.",
                                 "payload":{"answer":true}}
                                """.formatted(otherSubject, topicId)))
                .andExpect(status().isCreated())
                .andReturn();

        // The request named CSS but the topic lives under JavaScript. The topic is the more
        // specific statement of intent, so it decides.
        assertThat(Json.readString(result.getResponse().getContentAsString(), "subjectId"))
                .isEqualTo(subjectId);
    }

    @Test
    @DisplayName("questions in another user's space are invisible")
    void isolationBetweenUsers() throws Exception {
        String id = createMcq("Private question", "yes");
        TestUser stranger = registerUser("QuestionStranger");

        mockMvc.perform(get(url("/" + id)).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mcqBody("Injected", "no")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a subject from another space cannot be used")
    void crossSpaceSubjectRejected() throws Exception {
        String otherSpace = createSpace(user, "INTERVIEW", "Another Space");

        mockMvc.perform(post("/api/v1/spaces/" + otherSpace + "/questions")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mcqBody("Cross space", "no")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("subject.not_found"));
    }

    @Test
    @DisplayName("a question type that has not shipped yet is refused clearly")
    void unsupportedTypeRejected() throws Exception {
        mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","type":"CODING","difficulty":"HARD",
                                 "stem":"Write a debounce function","payload":{}}
                                """.formatted(subjectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("question.type_unsupported"));
    }

    @Test
    @DisplayName("archiving through a status edit is refused; DELETE is the way")
    void statusEditCannotArchive() throws Exception {
        String id = createMcq("Status question", "yes");

        mockMvc.perform(patch(url("/" + id))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ARCHIVED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("question.status_invalid"));

        mockMvc.perform(patch(url("/" + id))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"DRAFT"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));
    }

    // ------------------------------------------------------------------ helpers

    private String url(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/questions" + suffix;
    }

    private String mcqBody(String stem, String correctText) {
        return """
               {"subjectId":"%s","type":"MCQ","difficulty":"MEDIUM","stem":"%s",
                "explanation":"Because that is how it works.",
                "payload":{"options":[
                  {"id":"a","text":"%s","correct":true},
                  {"id":"b","text":"something else","correct":false},
                  {"id":"c","text":"a third thing","correct":false}]}}
               """.formatted(subjectId, stem, correctText);
    }

    private String createMcq(String stem, String correctText) throws Exception {
        MvcResult result = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mcqBody(stem, correctText)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    private String createFlashcard(String front, String back) throws Exception {
        MvcResult result = mockMvc.perform(post(url(""))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","type":"FLASHCARD","difficulty":"EASY",
                                 "stem":"%s","payload":{"front":"%s","back":"%s"}}
                                """.formatted(subjectId, front, front, back)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

    private String createSpace(TestUser owner, String typeKey, String name) throws Exception {
        MvcResult types = mockMvc.perform(get("/api/v1/preparation-types")
                .header(HttpHeaders.AUTHORIZATION, owner.bearer())).andReturn();
        String typeId = Json.<String>readList(types.getResponse().getContentAsString(),
                "[?(@.key=='" + typeKey + "')].id").getFirst();

        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer())
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

    private String createTopic(String subject, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","name":"%s"}
                                """.formatted(subject, name)))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }

}
