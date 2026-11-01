package com.patipp.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

class ChallengeIntegrationTest extends IntegrationTest {
    @Test
    void enrollIsAtomicAndRepeatableAndBriefsDoNotLeakSolutions() throws Exception {
        var user = registerUser("Guide");
        String space = enroll(user);
        assertThat(enroll(user)).isEqualTo(space);
        var result = mockMvc.perform(get("/api/v1/spaces/" + space + "/challenge")
                .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.days.length()").value(30))
                .andExpect(jsonPath("$.progress.length()").value(30))
                .andExpect(jsonPath("$.progress[0].completed").value(false)).andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("referenceSolution", "hints");
        var other = registerUser("OtherGuide");
        mockMvc.perform(get("/api/v1/spaces/" + space + "/challenge")
                .header("Authorization", other.bearer())).andExpect(status().isNotFound());
    }

    @Test
    void hintsSurviveReloadAndAssistanceCannotBeForgedByTheAnswer() throws Exception {
        var user = registerUser("Hints");
        String space = enroll(user);
        String base = "/api/v1/spaces/" + space;
        var started = mockMvc.perform(post(base + "/challenge/days/1/start")
                .header("Authorization", user.bearer())).andExpect(status().isOk())
                .andExpect(jsonPath("$.currentItem.hints.length()").value(0)).andReturn();
        String session = Json.readString(started.getResponse().getContentAsString(), "id");
        String sessionUrl = base + "/sessions/" + session;
        mockMvc.perform(post(sessionUrl + "/items/0/hint").header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hints.length()").value(1));
        mockMvc.perform(get(sessionUrl).header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.currentItem.presentation.hintsRevealed").value(1));
        mockMvc.perform(post(sessionUrl + "/answers").header("Authorization", user.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                  {"position":0,"answer":{"grade":3,"completed":true,"checksPassed":true,
                   "assistance":"INDEPENDENT","hintsUsed":0},"responseTimeMs":60000}
                  """))
                .andExpect(status().isOk());
        mockMvc.perform(get(base + "/challenge").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.progress[0].completed").value(true))
                .andExpect(jsonPath("$.progress[0].independent").value(false))
                .andExpect(jsonPath("$.progress[0].assistance").value("HINTS"));
    }

    @Test
    void solutionIsExplicitAndTimedSessionsRefuseHelp() throws Exception {
        var user = registerUser("Solution");
        String space = enroll(user);
        String base = "/api/v1/spaces/" + space;
        var started = mockMvc.perform(post(base + "/challenge/days/1/start")
                .header("Authorization", user.bearer())).andReturn();
        String session = Json.readString(started.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(base + "/sessions/" + session + "/items/0/solution")
                .header("Authorization", user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.presentation.revealedAnswer.referenceSolution").isNotEmpty());
        mockMvc.perform(post(base + "/sessions/" + session + "/answers")
                .header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":0,\"answer\":{\"grade\":1,\"completed\":false}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(base + "/challenge").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.progress[0].completed").value(false))
                .andExpect(jsonPath("$.progress[0].assistance").value("SOLUTION"))
                .andExpect(jsonPath("$.activity.length()").value(1));
        mockMvc.perform(post(base + "/sessions/" + session + "/complete")
                .header("Authorization", user.bearer())).andExpect(status().isOk());
        var timed = mockMvc.perform(post(base + "/challenge/days/27/start?timed=true")
                .header("Authorization", user.bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.mode").value("EXAM")).andReturn();
        String timedId = Json.readString(timed.getResponse().getContentAsString(), "id");
        mockMvc.perform(post(base + "/sessions/" + timedId + "/items/0/hint")
                .header("Authorization", user.bearer())).andExpect(status().isBadRequest());
    }

    @Test
    void unfinishedPracticeCannotClaimMasteryAndAnIndependentRetryCanCompleteTheCore() throws Exception {
        var user = registerUser("Incomplete");
        String space = enroll(user);
        String base = "/api/v1/spaces/" + space;
        String session = Json.readString(mockMvc.perform(post(base + "/challenge/days/1/start")
                .header("Authorization", user.bearer())).andReturn().getResponse().getContentAsString(), "id");
        mockMvc.perform(post(base + "/sessions/" + session + "/answers")
                .header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":0,\"answer\":{\"grade\":4,\"completed\":false}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.correct").value(false));
        mockMvc.perform(post(base + "/sessions/" + session + "/complete")
                .header("Authorization", user.bearer())).andExpect(status().isOk());
        String retry = Json.readString(mockMvc.perform(post(base + "/challenge/days/1/start")
                .header("Authorization", user.bearer())).andReturn().getResponse().getContentAsString(), "id");
        mockMvc.perform(post(base + "/sessions/" + retry + "/answers")
                .header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":0,\"answer\":{\"grade\":3,\"completed\":true}}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post(base + "/sessions/" + retry + "/answers")
                .header("Authorization", user.bearer()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"position\":0,\"answer\":{\"grade\":3,\"completed\":true,\"checksPassed\":true}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get(base + "/challenge").header("Authorization", user.bearer()))
                .andExpect(jsonPath("$.progress[0].completed").value(true))
                .andExpect(jsonPath("$.progress[0].independent").value(true))
                .andExpect(jsonPath("$.progress[0].attempts").value(2));
    }

    private String enroll(TestUser user) throws Exception {
        return Json.readString(mockMvc.perform(post("/api/v1/challenges/java-spring/enroll")
                .header("Authorization", user.bearer())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "spaceId");
    }
}
