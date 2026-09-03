package com.patipp.questions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class QuestionImportExportIntegrationTest extends IntegrationTest {

    private TestUser user;
    private String spaceId;
    private String subjectId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Import");
        spaceId = createSpace("ACADEMIC_EXAM", "Import Space");
        subjectId = createSubject("JavaScript");
        createTopic(subjectId, "Closures");
    }

    @Test
    @DisplayName("a dry run reports every row and writes nothing")
    void dryRunWritesNothing() throws Exception {
        String csv = """
                type,stem,difficulty,subject,option1,option2,option3,correct,explanation
                MCQ,Which keyword is block scoped?,EASY,JavaScript,var,let,function,2,let is block scoped.
                TRUE_FALSE,Closures capture their scope.,MEDIUM,JavaScript,,,,,Yes they do.
                """;

        MvcResult result = mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true))
                .andExpect(jsonPath("$.totalRows").value(2))
                .andExpect(jsonPath("$.imported").value(0))
                .andReturn();

        // TRUE_FALSE needs an answer column, which this file does not have.
        String body = result.getResponse().getContentAsString();
        assertThat(Json.readInt(body, "invalid")).isEqualTo(1);
        assertThat(Json.readInt(body, "valid")).isEqualTo(1);

        // Nothing reached the bank. That is the entire promise of a dry run.
        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    @DisplayName("problems are reported per row, with the spreadsheet line number")
    void reportsProblemsPerRow() throws Exception {
        String csv = """
                type,stem,subject,option1,option2,correct
                MCQ,Missing the correct column,JavaScript,one,two,
                MCQ,Correct points at an option that does not exist,JavaScript,one,two,9
                NONSENSE,Unknown type,JavaScript,,,
                MCQ,,JavaScript,one,two,1
                """;

        MvcResult result = mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalid").value(4))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Line numbers match what a spreadsheet shows, header included, so a problem can be
        // traced straight back to the row that caused it.
        assertThat(Json.<Integer>readList(body, "rows[*].line")).containsExactly(2, 3, 4, 5);
        assertThat(Json.<String>readList(body, "rows[*].outcome"))
                .containsOnly("INVALID");
        assertThat(Json.readString(body, "rows[0].problems[0].field")).isEqualTo("correct");
        assertThat(Json.readString(body, "rows[1].problems[0].message"))
                .contains("does not exist");
        assertThat(Json.readString(body, "rows[2].problems[0].message"))
                .contains("unknown question type");
        assertThat(Json.readString(body, "rows[3].problems[0].field")).isEqualTo("stem");
    }

    @Test
    @DisplayName("a real import writes the rows the dry run approved")
    void realImportWritesRows() throws Exception {
        String complete = """
                type,stem,difficulty,subject,topic,option1,option2,option3,correct,acceptedAnswers,back,tags
                MCQ,Which keyword is block scoped?,EASY,JavaScript,Closures,var,let,function,b,,,scope|basics
                SHORT_ANSWER,What runs async callbacks?,MEDIUM,JavaScript,,,,,,event loop|the event loop,,
                FLASHCARD,What is a closure?,EASY,JavaScript,Closures,,,,,,A function plus its captured scope.,
                """;

        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", complete, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.invalid").value(0))
                .andExpect(jsonPath("$.rows[0].questionId").isNotEmpty());

        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(3));

        // The MCQ resolved its subject and topic by name, and kept its tags.
        MvcResult mcq = mockMvc.perform(get(url("?type=MCQ"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String body = mcq.getResponse().getContentAsString();
        assertThat(Json.readString(body, "items[0].subjectId")).isEqualTo(subjectId);
        assertThat(Json.readString(body, "items[0].topicId")).isNotBlank();
        assertThat(Json.<String>readList(body, "items[0].tags")).containsExactly("scope", "basics");
    }

    @Test
    @DisplayName("the correct column accepts a letter or a number")
    void correctColumnAcceptsBothForms() throws Exception {
        String csv = """
                type,stem,subject,option1,option2,option3,correct
                MCQ,Answered by letter,JavaScript,alpha,beta,gamma,b
                MCQ,Answered by number,JavaScript,alpha,beta,gamma,2
                """;

        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, false)))
                .andExpect(jsonPath("$.imported").value(2));
    }

    @Test
    @DisplayName("duplicates inside one file are caught, not just against the existing bank")
    void duplicatesWithinTheFile() throws Exception {
        String csv = """
                type,stem,subject,option1,option2,correct
                MCQ,Repeated question,JavaScript,one,two,1
                MCQ,repeated QUESTION.,JavaScript,one,two,1
                """;

        MvcResult result = mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(1))
                .andExpect(jsonPath("$.duplicates").value(1))
                .andReturn();

        assertThat(Json.<String>readList(result.getResponse().getContentAsString(), "rows[*].outcome"))
                .containsExactly("IMPORTED", "DUPLICATE");
    }

    @Test
    @DisplayName("importing the same file twice adds nothing the second time")
    void reimportIsIdempotent() throws Exception {
        String csv = """
                type,stem,subject,option1,option2,correct
                MCQ,Only once please,JavaScript,one,two,1
                """;

        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, false)))
                .andExpect(jsonPath("$.imported").value(1));

        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, false)))
                .andExpect(jsonPath("$.imported").value(0))
                .andExpect(jsonPath("$.duplicates").value(1));

        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(1));
    }

    @Test
    @DisplayName("a question naming a subject that does not exist is reported, not guessed")
    void unknownSubjectReported() throws Exception {
        String csv = """
                type,stem,subject,option1,option2,correct
                MCQ,Filed under nothing,Astrophysics,one,two,1
                """;

        MvcResult result = mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, true)))
                .andExpect(jsonPath("$.invalid").value(1))
                .andReturn();

        assertThat(Json.readString(result.getResponse().getContentAsString(),
                "rows[0].problems[0].message")).contains("Astrophysics");
    }

    @Test
    @DisplayName("malformed JSON is reported as a problem with the file, not a server error")
    void malformedJson() throws Exception {
        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("JSON", "{ this is not json", true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalid").value(1))
                .andExpect(jsonPath("$.rows[0].problems[0].field").value("file"));
    }

    @Test
    @DisplayName("an unknown import format is rejected")
    void unknownFormat() throws Exception {
        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("XLSX", "anything", true)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("import.format_invalid"));
    }

    @Test
    @DisplayName("export then wipe then re-import reproduces the same bank")
    void roundTrip() throws Exception {
        // One row of each implemented format, so the round trip covers every payload shape.
        String csv = """
                type,stem,difficulty,subject,topic,option1,option2,option3,correct,answer,acceptedAnswers,back,explanation,tags
                MCQ,Which keyword is block scoped?,EASY,JavaScript,Closures,var,let,function,b,,,,let is block scoped.,scope
                SHORT_ANSWER,What runs async callbacks?,MEDIUM,JavaScript,,,,,,,event loop,,The event loop does.,async
                FLASHCARD,What is a closure?,HARD,JavaScript,Closures,,,,,,,A function plus its scope.,,memory
                TRUE_FALSE,Let is function scoped.,EASY,JavaScript,,,,,,false,,,No - it is block scoped.,scope
                """;

        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", csv, false)))
                .andExpect(jsonPath("$.invalid").value(0))
                .andExpect(jsonPath("$.imported").value(4));

        // 1. Export.
        MvcResult exported = mockMvc.perform(get(url("/export"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").value(4))
                .andReturn();
        String exportJson = exported.getResponse().getContentAsString();
        List<String> originalStems = Json.readList(exportJson, "questions[*].stem");

        // 2. Wipe.
        MvcResult all = mockMvc.perform(get(url("?size=100"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        for (String id : Json.<String>readList(all.getResponse().getContentAsString(), "items[*].id")) {
            mockMvc.perform(delete(url("/" + id)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                    .andExpect(status().isNoContent());
        }
        mockMvc.perform(get(url("")).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.totalItems").value(0));

        // 3. Re-import the exported document, unmodified.
        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("JSON", exportJson, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invalid").value(0))
                .andExpect(jsonPath("$.imported").value(4));

        // 4. Export again and compare. Whatever comes out must go back in.
        MvcResult second = mockMvc.perform(get(url("/export"))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        String secondJson = second.getResponse().getContentAsString();

        assertThat(Json.<String>readList(secondJson, "questions[*].stem"))
                .containsExactlyInAnyOrderElementsOf(originalStems);
        assertThat(Json.<String>readList(secondJson, "questions[*].type"))
                .containsExactlyInAnyOrderElementsOf(Json.readList(exportJson, "questions[*].type"));
        assertThat(Json.<String>readList(secondJson, "questions[*].subject"))
                .containsOnly("JavaScript");
    }

    @Test
    @DisplayName("import into another user's space is refused")
    void importIsolation() throws Exception {
        TestUser stranger = registerUser("ImportStranger");

        mockMvc.perform(post(url("/import"))
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody("CSV", "type,stem\nMCQ,x\n", true)))
                .andExpect(status().isNotFound());

        mockMvc.perform(get(url("/export")).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String url(String suffix) {
        return "/api/v1/spaces/" + spaceId + "/questions" + suffix;
    }

    private String importBody(String format, String content, boolean dryRun) {
        String escaped = content
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        return """
               {"format":"%s","content":"%s","dryRun":%s}
               """.formatted(format, escaped, dryRun);
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

    private void createTopic(String subject, String name) throws Exception {
        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","name":"%s"}
                                """.formatted(subject, name)))
                .andExpect(status().isCreated());
    }
}
