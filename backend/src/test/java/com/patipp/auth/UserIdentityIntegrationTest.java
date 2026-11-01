package com.patipp.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.patipp.auth.domain.AuthProvider;
import com.patipp.auth.domain.User;
import com.patipp.auth.domain.UserIdentity;
import com.patipp.auth.domain.UserIdentityRepository;
import com.patipp.auth.domain.UserRepository;
import com.patipp.support.IntegrationTest;
import com.patipp.support.Json;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/**
 * The V10 schema and the invariants it exists to hold.
 *
 * <p>These run against a real PostgreSQL because the guarantees under test are constraints,
 * not Java. A mock would assert that the code intends to be safe; only the database can say
 * whether it is.
 */
class UserIdentityIntegrationTest extends IntegrationTest {

    @Autowired
    private UserRepository users;

    @Autowired
    private UserIdentityRepository identities;

    @Test
    @DisplayName("a passwordless user persists, which is what V10 made possible")
    void providerUserPersistsWithoutAPassword() {
        User saved = users.saveAndFlush(User.fromProvider(unique("provider"), "Ada", "UTC"));

        User reloaded = users.findById(saved.id()).orElseThrow();
        assertThat(reloaded.hasPassword()).isFalse();
    }

    @Test
    @DisplayName("an identity is found by provider subject")
    void identityIsFoundBySubject() {
        String email = unique("linked");
        User user = users.saveAndFlush(User.fromProvider(email, "Ada", "UTC"));
        String subject = "google-" + System.nanoTime();

        identities.saveAndFlush(UserIdentity.link(
                user.id(), AuthProvider.GOOGLE, subject, email, Instant.now()));

        Optional<UserIdentity> found =
                identities.findByProviderAndExternalId(AuthProvider.GOOGLE, subject);

        assertThat(found).isPresent();
        assertThat(found.get().userId()).isEqualTo(user.id());
        assertThat(found.get().email()).isEqualTo(email);
    }

    @Test
    @DisplayName("one Google account cannot own two PATIPP accounts")
    void aSubjectCannotBeClaimedTwice() {
        String subject = "google-" + System.nanoTime();

        User first = users.saveAndFlush(User.fromProvider(unique("first"), "First", "UTC"));
        identities.saveAndFlush(UserIdentity.link(
                first.id(), AuthProvider.GOOGLE, subject, first.email(), Instant.now()));

        User second = users.saveAndFlush(User.fromProvider(unique("second"), "Second", "UTC"));
        UserIdentity stolen = UserIdentity.link(
                second.id(), AuthProvider.GOOGLE, subject, second.email(), Instant.now());

        // The whole point of user_identities_subject_unique. Without it, a bug in the
        // find-or-create path could silently point one Google login at two learners' data.
        assertThatThrownBy(() -> identities.saveAndFlush(stolen))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("password login on a provider-only account reveals nothing about it")
    void providerOnlyAccountLeaksNothingToPasswordLogin() throws Exception {
        // The usability trade-off is deliberate: a learner who signed up with Google and
        // then tries a password gets the same answer as someone probing a stranger's
        // address. Telling them apart would turn login into an account-enumeration oracle.
        String providerOnly = unique("googleonly");
        users.saveAndFlush(User.fromProvider(providerOnly, "Google Only", "UTC"));

        Failure onProviderAccount = attemptPasswordLogin(providerOnly);
        Failure onUnknownAddress = attemptPasswordLogin(unique("nobody"));

        assertThat(onProviderAccount.status()).isEqualTo(401);
        assertThat(onProviderAccount.code()).isEqualTo("auth.invalid_credentials");
        assertThat(onProviderAccount)
                .as("the two responses must not differ in any way a prober could read")
                .isEqualTo(onUnknownAddress);
    }

    private Failure attemptPasswordLogin(String email) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"correct-horse-battery"}
                                """.formatted(email)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        return new Failure(result.getResponse().getStatus(),
                Json.readString(body, "code"), Json.readString(body, "detail"));
    }

    private record Failure(int status, String code, String detail) {
    }

    private static String unique(String label) {
        return label + "-" + System.nanoTime() + "@example.com";
    }
}
