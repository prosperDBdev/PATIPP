package com.patipp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Google sign-in end to end, with Google replaced by a stub.
 *
 * <p>Only the decoder is faked, and only because the alternative is a test that needs
 * Google's signing keys and a private key to match them. Everything after it is the real
 * thing: the real endpoint, the real account resolution, the real session issuing, the real
 * database and its constraints.
 *
 * <p>The distinction matters for what these tests are worth. They do not prove PATIPP
 * verifies Google's signature - {@code GoogleIdTokenConfig} does that and is exercised by
 * the deployment itself. They prove that a verified identity ends up attached to exactly
 * one PATIPP account, which is the part with data loss on the other side of it.
 */
class GoogleSignInIntegrationTest extends IntegrationTest {

    @Test
    void publicProviderSettingsExposeOnlyThePublicClientId() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/auth/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.googleEnabled").value(true))
                .andExpect(jsonPath("$.googleClientId").value("test-client.apps.googleusercontent.com"))
                .andExpect(jsonPath("$.accessToken").doesNotExist())
                .andExpect(jsonPath("$.clientSecret").doesNotExist());
    }

    @MockitoBean(name = "googleIdTokenDecoder")
    private JwtDecoder googleIdTokenDecoder;

    @Test
    @DisplayName("a first Google sign-in creates the account and issues a PATIPP session")
    void firstSignInCreatesAccount() throws Exception {
        String email = unique("newcomer");
        stubGoogleReturning("google-" + System.nanoTime(), email, true, "Ada Lovelace");

        MvcResult result = signInWithGoogle()
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.user.email").value(email))
                .andExpect(jsonPath("$.user.displayName").value("Ada Lovelace"))
                // The session is PATIPP's own from here on: same cookie, same rotation,
                // same reuse detection as a password sign-in.
                .andExpect(cookie().exists("patipp_refresh"))
                .andExpect(cookie().httpOnly("patipp_refresh", true))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .as("no Google token or refresh token may appear in the body")
                .doesNotContain("idToken")
                .doesNotContain("refreshToken");
    }

    @Test
    @DisplayName("signing in again with the same Google account reuses the same PATIPP account")
    void repeatedSignInIsTheSameAccount() throws Exception {
        String subject = "google-" + System.nanoTime();
        String email = unique("returning");

        stubGoogleReturning(subject, email, true, "Ada");
        String firstId = userIdFrom(signInWithGoogle().andExpect(status().isOk()).andReturn());

        stubGoogleReturning(subject, email, true, "Ada");
        String secondId = userIdFrom(signInWithGoogle().andExpect(status().isOk()).andReturn());

        assertThat(secondId)
                .as("a second sign-in must not create a second account")
                .isEqualTo(firstId);
    }

    @Test
    @DisplayName("a verified Google address claims the existing password account, and the password still works")
    void verifiedAddressLinksToExistingAccount() throws Exception {
        TestUser existing = registerUser("Linkable");

        stubGoogleReturning("google-" + System.nanoTime(), existing.email(), true, "Linkable");
        String googleUserId = userIdFrom(signInWithGoogle().andExpect(status().isOk()).andReturn());

        assertThat(googleUserId)
                .as("the learner must land on their own study history, not a second empty account")
                .isEqualTo(existing.id());

        // Linking adds a way in; it does not take one away. An account that silently lost
        // its password would lock out anyone who used Google once out of curiosity.
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s"}
                                """.formatted(existing.email(), existing.password())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(existing.id()));
    }

    @Test
    @DisplayName("a new account takes the browser's timezone, since Google does not supply one")
    void newAccountTakesBrowserTimezone() throws Exception {
        stubGoogleReturning("google-" + System.nanoTime(), unique("zoned"), true, "Ada");

        signInWithGoogle("Europe/London")
                .andExpect(status().isOk())
                // Without this the learner sits in UTC and their study days are attributed
                // to the wrong date, which is the existing UTC-bucketing gap made worse.
                .andExpect(jsonPath("$.user.timezone").value("Europe/London"));
    }

    @Test
    @DisplayName("an existing account keeps its own timezone, whatever machine signs in")
    void existingAccountKeepsItsTimezone() throws Exception {
        // registerUser creates the account in Europe/London.
        TestUser existing = registerUser("Traveller");
        stubGoogleReturning("google-" + System.nanoTime(), existing.email(), true, "Traveller");

        signInWithGoogle("America/New_York")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(existing.id()))
                .andExpect(jsonPath("$.user.timezone").value("Europe/London"));
    }

    @Test
    @DisplayName("a timezone the JVM does not recognise falls back to UTC instead of failing")
    void unknownTimezoneFallsBackToUtc() throws Exception {
        stubGoogleReturning("google-" + System.nanoTime(), unique("badzone"), true, "Ada");

        // It arrives from the browser, so it is an untrusted string going into a constrained
        // column. Rejecting the whole sign-in over it would be a poor trade.
        signInWithGoogle("Mars/Olympus_Mons")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.timezone").value("UTC"));
    }

    @Test
    @DisplayName("an unverified Google address is refused before any account is touched")
    void unverifiedAddressIsRefused() throws Exception {
        TestUser existing = registerUser("Victim");

        // The attack this blocks: claim someone's address at an identity provider that does
        // not check, then sign in and inherit their account.
        stubGoogleReturning("google-" + System.nanoTime(), existing.email(), false, "Not Them");

        signInWithGoogle()
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.google_email_unverified"));
    }

    @Test
    @DisplayName("a token Google did not sign is refused")
    void forgedTokenIsRefused() throws Exception {
        given(googleIdTokenDecoder.decode(anyString()))
                .willThrow(new BadJwtException("signature mismatch"));

        signInWithGoogle()
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("auth.google_token_invalid"));
    }

    @Test
    @DisplayName("an empty token is a validation failure, not a 500")
    void emptyTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/oauth/google")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"idToken":""}
                                """))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions signInWithGoogle() throws Exception {
        return mockMvc.perform(post("/api/v1/auth/oauth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idToken":"stubbed-google-id-token"}
                        """));
    }

    private org.springframework.test.web.servlet.ResultActions signInWithGoogle(String timezone)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/oauth/google")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"idToken":"stubbed-google-id-token","timezone":"%s"}
                        """.formatted(timezone)));
    }

    private void stubGoogleReturning(String subject, String email, boolean verified, String name) {
        Instant now = Instant.now();
        Jwt.Builder builder = Jwt.withTokenValue("stubbed-google-id-token")
                .header("alg", "RS256")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300));

        Map.of("sub", subject,
                        "email", email,
                        "email_verified", verified,
                        "name", name)
                .forEach(builder::claim);

        given(googleIdTokenDecoder.decode(anyString())).willReturn(builder.build());
    }

    private static String userIdFrom(MvcResult result) throws Exception {
        return Json.readString(result.getResponse().getContentAsString(), "user.id");
    }

    private static String unique(String label) {
        return label.toLowerCase() + "-" + System.nanoTime() + "@example.com";
    }
}
