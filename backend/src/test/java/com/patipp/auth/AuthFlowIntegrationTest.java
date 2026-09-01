package com.patipp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

class AuthFlowIntegrationTest extends IntegrationTest {

    @Test
    @DisplayName("registration returns an access token and sets an httpOnly refresh cookie")
    void registerIssuesSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"Ada@Example.COM","password":"correct-horse-battery",
                                 "displayName":"Ada","timezone":"Europe/London"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                // The email is normalised on the way in, so case can never create a duplicate.
                .andExpect(jsonPath("$.user.email").value("ada@example.com"))
                .andExpect(cookie().exists("patipp_refresh"))
                .andExpect(cookie().httpOnly("patipp_refresh", true))
                .andReturn();

        // The refresh token must never appear in the response body, where script could read it.
        assertThat(result.getResponse().getContentAsString()).doesNotContain("refreshToken");
        assertThat(result.getResponse().getCookie("patipp_refresh").getPath())
                .as("cookie is scoped so ordinary API calls never carry it")
                .isEqualTo("/api/v1/auth");
    }

    @Test
    @DisplayName("registering the same address twice is rejected, ignoring case")
    void duplicateEmailIsRejected() throws Exception {
        String email = "dup-" + System.nanoTime() + "@example.com";
        String body = """
                {"email":"%s","password":"correct-horse-battery","displayName":"First"}
                """.formatted(email);

        mockMvc.perform(post("/api/v1/auth/register")
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery","displayName":"Second"}
                                """.formatted(email.toUpperCase())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("auth.email_taken"));
    }

    @Test
    @DisplayName("a short password is rejected with a field-level error")
    void shortPasswordIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"short@example.com","password":"abc","displayName":"Short"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("validation.failed"))
                .andExpect(jsonPath("$.errors[0].field").value("password"));
    }

    @Test
    @DisplayName("a wrong password and an unknown account are indistinguishable")
    void loginFailuresAreIndistinguishable() throws Exception {
        TestUser user = registerUser("Grace");

        MvcResult wrongPassword = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"not-the-right-password"}
                                """.formatted(user.email())))
                .andExpect(status().isUnauthorized())
                .andReturn();

        MvcResult unknownAccount = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"nobody-here@example.com","password":"not-the-right-password"}
                                """))
                .andExpect(status().isUnauthorized())
                .andReturn();

        // If these differed, the endpoint would be an oracle for which addresses are registered.
        assertThat(Json.readString(wrongPassword.getResponse().getContentAsString(), "code"))
                .isEqualTo(Json.readString(unknownAccount.getResponse().getContentAsString(), "code"))
                .isEqualTo("auth.invalid_credentials");
        assertThat(Json.readString(wrongPassword.getResponse().getContentAsString(), "detail"))
                .isEqualTo(Json.readString(unknownAccount.getResponse().getContentAsString(), "detail"));
    }

    @Test
    @DisplayName("a protected endpoint requires a token and returns problem+json without one")
    void protectedEndpointRequiresToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.required"));
    }

    @Test
    @DisplayName("a valid bearer token identifies the caller")
    void bearerTokenIdentifiesCaller() throws Exception {
        TestUser user = registerUser("Alan");

        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, user.bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(user.id()))
                .andExpect(jsonPath("$.email").value(user.email()))
                // The hash must never be serialised, no matter how the DTO evolves.
                .andExpect(jsonPath("$.passwordHash").doesNotExist());
    }

    @Test
    @DisplayName("a garbage token is rejected rather than accepted or crashed on")
    void garbageTokenIsRejected() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer not.a.real.token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("refresh rotates the cookie and issues a new access token")
    void refreshRotatesTheToken() throws Exception {
        TestUser user = registerUser("Rotate");

        MvcResult refreshed = mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(user.refreshCookie()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(cookie().exists("patipp_refresh"))
                .andReturn();

        Cookie rotated = refreshed.getResponse().getCookie("patipp_refresh");
        assertThat(rotated.getValue())
                .as("rotation must issue a genuinely new token, not re-send the old one")
                .isNotEqualTo(user.refreshCookie().getValue());
    }

    @Test
    @DisplayName("reusing a rotated refresh token revokes every session for that user")
    void refreshReuseRevokesEverything() throws Exception {
        TestUser user = registerUser("Reuse");

        MvcResult first = mockMvc.perform(post("/api/v1/auth/refresh").cookie(user.refreshCookie()))
                .andExpect(status().isOk())
                .andReturn();
        Cookie rotated = first.getResponse().getCookie("patipp_refresh");

        // Presenting the spent token: either a stale replay or a stolen copy. Indistinguishable,
        // so the safe reading is that it leaked.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(user.refreshCookie()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.refresh_reused"));

        // The consequence that matters: the thief's freshly rotated token dies too.
        mockMvc.perform(post("/api/v1/auth/refresh").cookie(rotated))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout revokes the session and clears the cookie")
    void logoutRevokesSession() throws Exception {
        TestUser user = registerUser("Bye");

        mockMvc.perform(post("/api/v1/auth/logout").cookie(user.refreshCookie()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("patipp_refresh", 0));

        mockMvc.perform(post("/api/v1/auth/refresh").cookie(user.refreshCookie()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("logout without a cookie still succeeds")
    void logoutIsIdempotent() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")).andExpect(status().isNoContent());
    }

    @Test
    @DisplayName("every response carries a correlation id")
    void responsesCarryCorrelationId() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getHeader("X-Correlation-Id"))
                        .isNotBlank());
    }
}
