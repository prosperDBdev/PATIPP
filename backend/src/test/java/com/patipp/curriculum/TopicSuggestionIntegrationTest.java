package com.patipp.curriculum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

class TopicSuggestionIntegrationTest extends IntegrationTest {

    private TestUser user;
    private String spaceId;

    @BeforeEach
    void setUp() throws Exception {
        user = registerUser("Suggest");
        spaceId = createSpace("ACADEMIC_EXAM", "Suggestion Space");
    }

    @Test
    @DisplayName("a known subject gets a short, non-empty list of starter topics")
    void suggestsForKnownSubject() throws Exception {
        String subjectId = createSubject("HTML");

        MvcResult result = mockMvc.perform(get(suggestUrl(subjectId))
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.matchedSubject").value("HTML"))
                .andExpect(jsonPath("$.source").value("CATALOGUE"))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        // Short on purpose. A subject split into thirty topics looks thorough and is
        // miserable to work through, and no single topic then gathers enough attempts for
        // mastery to mean anything.
        assertThat(Json.<String>readList(body, "topics[*].name").size()).isBetween(4, 12);
        assertThat(Json.<String>readList(body, "topics[*].name")).contains("Semantic elements");
        assertThat(Json.<String>readList(body, "topics[*].description")).isNotEmpty();
    }

    @Test
    @DisplayName("aliases resolve, so \"js\" and \"react-native\" both match")
    void aliasesResolve() throws Exception {
        String js = createSubject("js");
        mockMvc.perform(get(suggestUrl(js)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.matched").value(true))
                // The canonical name is returned so the user can see what was matched.
                .andExpect(jsonPath("$.matchedSubject").value("JavaScript"));

        String rn = createSubject("react-native");
        mockMvc.perform(get(suggestUrl(rn)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.matched").value(true))
                .andExpect(jsonPath("$.matchedSubject").value("React Native"));
    }

    @Test
    @DisplayName("an unknown subject is an ordinary empty answer, not an error")
    void unknownSubjectIsNotAnError() throws Exception {
        String subjectId = createSubject("Advanced Ceramics");

        mockMvc.perform(get(suggestUrl(subjectId)).header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.matched").value(false))
                .andExpect(jsonPath("$.topics.length()").value(0));
    }

    @Test
    @DisplayName("accepting suggestions creates them in one request")
    void acceptSuggestions() throws Exception {
        String subjectId = createSubject("CSS");

        MvcResult suggestions = mockMvc.perform(get(suggestUrl(subjectId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        var names = Json.<String>readList(suggestions.getResponse().getContentAsString(),
                "topics[*].name");

        MvcResult created = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics/bulk")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody(subjectId, names)))
                .andExpect(status().isCreated())
                .andReturn();

        assertThat(Json.<String>readList(created.getResponse().getContentAsString(), "[*].name"))
                .containsExactlyElementsOf(names);

        // They are real top-level topics in the tree, indistinguishable from hand-typed ones.
        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/curriculum")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$[?(@.name=='CSS')].topics.length()").value(names.size()));
    }

    @Test
    @DisplayName("suggestions already accepted stop being offered")
    void acceptedSuggestionsDisappear() throws Exception {
        String subjectId = createSubject("Git");

        MvcResult first = mockMvc.perform(get(suggestUrl(subjectId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        var all = Json.<String>readList(first.getResponse().getContentAsString(), "topics[*].name");

        // Accept only the first two.
        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics/bulk")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bulkBody(subjectId, all.subList(0, 2))))
                .andExpect(status().isCreated());

        MvcResult second = mockMvc.perform(get(suggestUrl(subjectId))
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();

        // The list shrinks as it is used, so the panel does not keep re-offering the same
        // topics the user has already taken.
        assertThat(Json.<String>readList(second.getResponse().getContentAsString(), "topics[*].name"))
                .hasSize(all.size() - 2)
                .doesNotContainAnyElementsOf(all.subList(0, 2));
    }

    @Test
    @DisplayName("bulk add skips names that already exist rather than failing the batch")
    void bulkSkipsDuplicates() throws Exception {
        String subjectId = createSubject("Docker");

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","name":"Dockerfile"}
                                """.formatted(subjectId)))
                .andExpect(status().isCreated());

        MvcResult created = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics/bulk")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","names":["Dockerfile","Volumes and data","dockerfile"]}
                                """.formatted(subjectId)))
                .andExpect(status().isCreated())
                .andReturn();

        // One already existed, one was a case-insensitive repeat within the request itself.
        // The user asked for these topics to be present; refusing the whole batch over a
        // collision on one would be unhelpful.
        assertThat(Json.<String>readList(created.getResponse().getContentAsString(), "[*].name"))
                .containsExactly("Volumes and data");
    }

    @Test
    @DisplayName("an empty or oversized bulk request is rejected")
    void bulkValidation() throws Exception {
        String subjectId = createSubject("Java");

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics/bulk")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","names":[]}
                                """.formatted(subjectId)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("topic.names_required"));

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            many.append(i > 0 ? "," : "").append("\"Topic ").append(i).append("\"");
        }
        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics/bulk")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","names":[%s]}
                                """.formatted(subjectId, many)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("topic.too_many"));
    }

    @Test
    @DisplayName("suggestions and bulk add respect space ownership")
    void isolation() throws Exception {
        String subjectId = createSubject("HTML");
        TestUser stranger = registerUser("SuggestStranger");

        mockMvc.perform(get(suggestUrl(subjectId)).header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics/bulk")
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectId":"%s","names":["Injected"]}
                                """.formatted(subjectId)))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------ helpers

    private String suggestUrl(String subjectId) {
        return "/api/v1/spaces/" + spaceId + "/subjects/" + subjectId + "/topic-suggestions";
    }

    private String bulkBody(String subjectId, java.util.List<String> names) {
        String quoted = names.stream()
                .map(name -> "\"" + name.replace("\"", "\\\"") + "\"")
                .reduce((a, b) -> a + "," + b)
                .orElse("");
        return """
               {"subjectId":"%s","names":[%s]}
               """.formatted(subjectId, quoted);
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
}
