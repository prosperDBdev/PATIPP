package com.patipp.curriculum;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class CurriculumIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("a full NIIT curriculum can be built and read back as a tree")
    void buildsTheRealCurriculum() throws Exception {
        TestUser user = registerUser("Niit");
        String spaceId = createSpace(user, "ACADEMIC_EXAM", "NIIT Semester 2");

        for (String subject : List.of("HTML", "CSS", "JavaScript", "React", "React Native")) {
            createSubject(user, spaceId, subject);
        }

        String reactId = subjectId(user, spaceId, "React");
        String hooksId = createTopic(user, spaceId, reactId, null, "Hooks");
        createTopic(user, spaceId, reactId, null, "State Management");
        createTopic(user, spaceId, null, hooksId, "useEffect dependencies");

        MvcResult result = mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/curriculum")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(5))
                .andReturn();

        String body = result.getResponse().getContentAsString();

        assertThat(Json.<String>readList(body, "[*].name"))
                .containsExactly("HTML", "CSS", "JavaScript", "React", "React Native");

        // A JsonPath filter always yields a list, so these read the single match out of it.
        // The subtopic is nested under its parent, not flattened alongside it.
        assertThat(Json.<String>readList(body,
                "[?(@.name=='React')].topics[?(@.name=='Hooks')].children[0].name"))
                .containsExactly("useEffect dependencies");

        // The materialised path is what breadcrumbs and later analytics group on.
        assertThat(Json.<String>readList(body,
                "[?(@.name=='React')].topics[?(@.name=='Hooks')].children[0].path"))
                .containsExactly("Hooks / useEffect dependencies");
    }

    @Test
    @DisplayName("topic nesting is capped so the tree stays meaningful")
    void topicDepthIsCapped() throws Exception {
        TestUser user = registerUser("Deep");
        String spaceId = createSpace(user, "GENERAL_TEST", "Deep Space");
        String subjectId = createSubject(user, spaceId, "Root Subject");

        String level0 = createTopic(user, spaceId, subjectId, null, "Level 0");
        String level1 = createTopic(user, spaceId, null, level0, "Level 1");
        String level2 = createTopic(user, spaceId, null, level1, "Level 2");

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"parentTopicId":"%s","name":"Level 3"}
                                """.formatted(level2)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("topic.too_deep"));
    }

    @Test
    @DisplayName("renaming a topic rewrites the paths of everything beneath it")
    void renamingRebuildsDescendantPaths() throws Exception {
        TestUser user = registerUser("Rename");
        String spaceId = createSpace(user, "GENERAL_TEST", "Rename Space");
        String subjectId = createSubject(user, spaceId, "Frontend");

        String parent = createTopic(user, spaceId, subjectId, null, "Hooks");
        createTopic(user, spaceId, null, parent, "useEffect");

        mockMvc.perform(patch("/api/v1/spaces/" + spaceId + "/topics/" + parent)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"React Hooks"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.path").value("React Hooks"));

        MvcResult result = mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/curriculum")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andReturn();

        // A stale path is worse than none: it would show the old name in every breadcrumb.
        assertThat(Json.readString(result.getResponse().getContentAsString(),
                "[0].topics[0].children[0].path"))
                .isEqualTo("React Hooks / useEffect");
    }

    @Test
    @DisplayName("archiving a subject archives its topics too")
    void archivingSubjectCascadesToTopics() throws Exception {
        TestUser user = registerUser("Cascade");
        String spaceId = createSpace(user, "GENERAL_TEST", "Cascade Space");
        String subjectId = createSubject(user, spaceId, "Doomed");
        createTopic(user, spaceId, subjectId, null, "Doomed Topic");

        mockMvc.perform(delete("/api/v1/spaces/" + spaceId + "/subjects/" + subjectId)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        // A topic that outlived its subject would be unreachable but still selectable by the
        // question engine later, which is exactly the kind of orphan that is hard to notice.
        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/curriculum")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    @DisplayName("archiving a topic archives its subtopics")
    void archivingTopicCascadesToSubtopics() throws Exception {
        TestUser user = registerUser("TopicCascade");
        String spaceId = createSpace(user, "GENERAL_TEST", "Topic Cascade");
        String subjectId = createSubject(user, spaceId, "Subject");

        String parent = createTopic(user, spaceId, subjectId, null, "Parent");
        createTopic(user, spaceId, null, parent, "Child");

        mockMvc.perform(delete("/api/v1/spaces/" + spaceId + "/topics/" + parent)
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/curriculum")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(jsonPath("$[0].topics.length()").value(0));
    }

    @Test
    @DisplayName("subject names are unique within a space but free across spaces")
    void subjectNamesAreScopedToTheSpace() throws Exception {
        TestUser user = registerUser("Scoped");
        String niit = createSpace(user, "ACADEMIC_EXAM", "NIIT Semester 2");
        String interview = createSpace(user, "INTERVIEW", "React Interview");

        createSubject(user, niit, "React");

        mockMvc.perform(post("/api/v1/spaces/" + niit + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"react"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("subject.name_taken"));

        // The same subject name in a different space is a completely different thing.
        mockMvc.perform(post("/api/v1/spaces/" + interview + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"React"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("curriculum is invisible across users and across spaces")
    void curriculumIsIsolated() throws Exception {
        TestUser owner = registerUser("CurriculumOwner");
        TestUser stranger = registerUser("CurriculumStranger");

        String ownerSpace = createSpace(owner, "ACADEMIC_EXAM", "Private Curriculum");
        String subjectId = createSubject(owner, ownerSpace, "Secret Subject");

        mockMvc.perform(get("/api/v1/spaces/" + ownerSpace + "/curriculum")
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/v1/spaces/" + ownerSpace + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, stranger.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Injected"}
                                """))
                .andExpect(status().isNotFound());

        // A subject id from another space must not resolve, even for its rightful owner.
        String otherSpace = createSpace(owner, "GENERAL_TEST", "Other Space");
        mockMvc.perform(patch("/api/v1/spaces/" + otherSpace + "/subjects/" + subjectId)
                        .header(HttpHeaders.AUTHORIZATION, owner.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Moved"}
                                """))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("subject.not_found"));
    }

    @Test
    @DisplayName("an invalid colour is rejected with a field error")
    void invalidColourIsRejected() throws Exception {
        TestUser user = registerUser("Colour");
        String spaceId = createSpace(user, "GENERAL_TEST", "Colour Space");

        mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/subjects")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"Bad Colour","color":"blue"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("color"));
    }

    // ------------------------------------------------------------------ helpers

    private String createSpace(TestUser user, String typeKey, String name) throws Exception {
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

    private String createSubject(TestUser user, String spaceId, String name) throws Exception {
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

    private String subjectId(TestUser user, String spaceId, String name) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/v1/spaces/" + spaceId + "/curriculum")
                .header(HttpHeaders.AUTHORIZATION, user.bearer())).andReturn();
        return Json.<String>readList(result.getResponse().getContentAsString(),
                "[?(@.name=='" + name + "')].id").getFirst();
    }

    private String createTopic(TestUser user, String spaceId, String subjectId,
                               String parentTopicId, String name) throws Exception {
        String payload = parentTopicId != null
                ? """
                  {"parentTopicId":"%s","name":"%s"}
                  """.formatted(parentTopicId, name)
                : """
                  {"subjectId":"%s","name":"%s"}
                  """.formatted(subjectId, name);

        MvcResult result = mockMvc.perform(post("/api/v1/spaces/" + spaceId + "/topics")
                        .header(HttpHeaders.AUTHORIZATION, user.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();
        return Json.readString(result.getResponse().getContentAsString(), "id");
    }
}
