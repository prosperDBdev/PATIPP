package com.patipp.preparations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class PreparationSpaceIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("the six system preparation types are available with their blueprints")
    void systemTypesAreSeeded() throws Exception {
        TestUser user = registerUser("Types");

        MvcResult result = mockMvc.perform(get("/api/v1/preparation-types")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        List<String> keys = Json.readList(body, "[*].key");

        assertThat(keys).containsExactlyInAnyOrder(
                "ACADEMIC_EXAM", "INTERVIEW", "CERTIFICATION",
                "CODING_TEST", "GENERAL_TEST", "CUSTOM");

        // The blueprint is the extensibility mechanism; if it were empty the whole design
        // would be decorative.
        assertThat(Json.readList(body, "[?(@.key=='INTERVIEW')].blueprint.sessionModes"))
                .isNotEmpty();
    }

    @Test
    @DisplayName("a space can be created against a type and echoes its merged settings")
    void createSpace() throws Exception {
        TestUser user = registerUser("Create");
        String typeId = typeIdFor(user, "ACADEMIC_EXAM");
        String examDate = LocalDate.now().plusMonths(13).toString();

        mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"NIIT Semester 2 Exams",
                                 "description":"Semester 2","targetDate":"%s","targetScore":85}
                                """.formatted(typeId, examDate)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value("NIIT Semester 2 Exams"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.targetScore").value(85))
                .andExpect(jsonPath("$.preparationType.key").value("ACADEMIC_EXAM"))
                // Inherited straight from the type blueprint, with no space-level override.
                .andExpect(jsonPath("$.effectiveSettings.scoringPolicy").value("PARTIAL_CREDIT"))
                .andExpect(jsonPath("$.daysUntilTarget").isNumber());
    }

    @Test
    @DisplayName("space config is merged over the type blueprint, key by key")
    void spaceConfigOverridesBlueprint() throws Exception {
        TestUser user = registerUser("Merge");
        String typeId = typeIdFor(user, "ACADEMIC_EXAM");

        MvcResult result = mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"Custom Difficulty Space",
                                 "config":{"targetSuccessRate":0.6,"defaults":{"sessionLength":5}}}
                                """.formatted(typeId)))
                .andExpect(status().isCreated())
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Overridden at the top level.
        assertThat(Json.readString(body, "effectiveSettings.targetSuccessRate")).isEqualTo("0.6");
        // Overridden inside a nested object...
        assertThat(Json.readString(body, "effectiveSettings.defaults.sessionLength")).isEqualTo("5");
        // ...while its siblings are inherited rather than wiped out. This is the whole point
        // of merging nested objects instead of replacing them.
        assertThat(Json.readString(body, "effectiveSettings.defaults.examDurationMinutes"))
                .isEqualTo("60");
        // Untouched keys survive.
        assertThat(Json.readString(body, "effectiveSettings.schedulerPolicy")).isEqualTo("FSRS_V1");
    }

    @Test
    @DisplayName("two spaces cannot share a name, but an archived name can be reused")
    void spaceNamesAreUniquePerUser() throws Exception {
        TestUser user = registerUser("Names");
        String typeId = typeIdFor(user, "INTERVIEW");

        String spaceId = createSpace(user, typeId, "Java Backend Interview");

        mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"java backend interview"}
                                """.formatted(typeId)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("space.name_taken"));

        mockMvc.perform(delete("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"Java Backend Interview"}
                                """.formatted(typeId)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("one user cannot see or touch another user's space, and gets 404 not 403")
    void spacesAreIsolatedBetweenUsers() throws Exception {
        TestUser owner = registerUser("Owner");
        TestUser stranger = registerUser("Stranger");

        String spaceId = createSpace(owner, typeIdFor(owner, "CERTIFICATION"), "AWS Certification");

        // 404 rather than 403 throughout: a 403 would confirm the id is real, which turns
        // these endpoints into a way to enumerate other people's spaces.
        mockMvc.perform(get("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("space.not_found"));

        mockMvc.perform(patch("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Hijacked"}
                                """))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        // The stranger's own listing is empty, and the owner's space is intact.
        mockMvc.perform(get("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("AWS Certification"));
    }

    @Test
    @DisplayName("a user's spaces are independent of each other")
    void oneUserHasManyIndependentSpaces() throws Exception {
        TestUser user = registerUser("Multi");

        createSpace(user, typeIdFor(user, "ACADEMIC_EXAM"), "NIIT Semester 2");
        createSpace(user, typeIdFor(user, "INTERVIEW"), "Java Backend Interview");
        createSpace(user, typeIdFor(user, "CERTIFICATION"), "AWS Certification");

        MvcResult result = mockMvc.perform(get("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andReturn();

        // Three spaces, three different preparation types, one engine.
        assertThat(Json.<String>readList(result.getResponse().getContentAsString(),
                "[*].preparationType.key"))
                .containsExactlyInAnyOrder("ACADEMIC_EXAM", "INTERVIEW", "CERTIFICATION");
    }

    @Test
    @DisplayName("archiving hides a space from the default listing but keeps it retrievable")
    void archivingIsSoftDelete() throws Exception {
        TestUser user = registerUser("Archive");
        String spaceId = createSpace(user, typeIdFor(user, "GENERAL_TEST"), "Temporary Space");

        mockMvc.perform(delete("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/spaces").header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.length()").value(0));

        mockMvc.perform(get("/api/v1/spaces?includeArchived=true")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("ARCHIVED"));

        // The history is still there, which is the entire reason this is not a hard delete.
        mockMvc.perform(get("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").isNotEmpty());
    }

    @Test
    @DisplayName("a target date in the past is rejected")
    void pastTargetDateIsRejected() throws Exception {
        TestUser user = registerUser("Past");

        mockMvc.perform(post("/api/v1/spaces")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"preparationTypeId":"%s","name":"Yesterday","targetDate":"%s"}
                                """.formatted(typeIdFor(user, "GENERAL_TEST"),
                                LocalDate.now().minusDays(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("space.target_date_past"));
    }

    @Test
    @DisplayName("a space cannot be archived through a status edit")
    void statusEditCannotArchive() throws Exception {
        TestUser user = registerUser("Status");
        String spaceId = createSpace(user, typeIdFor(user, "GENERAL_TEST"), "Status Space");

        mockMvc.perform(patch("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"ARCHIVED"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("space.status_invalid"));

        mockMvc.perform(patch("/api/v1/spaces/" + spaceId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PAUSED"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAUSED"));
    }

    // ------------------------------------------------------------------ helpers

    private String typeIdFor(TestUser user, String key) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/preparation-types")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andReturn();
        List<String> ids = Json.readList(result.getResponse().getContentAsString(),
                "[?(@.key=='" + key + "')].id");
        return ids.getFirst();
    }

    private String createSpace(TestUser user, String typeId, String name) throws Exception {
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
}
