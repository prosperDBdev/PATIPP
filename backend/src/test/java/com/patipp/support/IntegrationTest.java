package com.patipp.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.patipp.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Base for tests that exercise the real HTTP stack against a real PostgreSQL.
 *
 * <p>Transactional, so each test rolls back and leaves the database as it found it. The
 * Flyway-seeded preparation types survive because they were committed when the container
 * started, which is exactly the split we want: reference data is shared, test data is not.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTest {

    @Autowired
    protected MockMvc mockMvc;

    /**
     * Registers a user and returns everything a test needs to act as them.
     *
     * <p>Emails are made unique per call so a test never collides with another, and so a
     * test that accidentally leaves data behind cannot poison the next one.
     */
    protected TestUser registerUser(String label) throws Exception {
        String email = label.toLowerCase() + "-" + System.nanoTime() + "@example.com";
        String password = "correct-horse-battery";

        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"%s","password":"%s","displayName":"%s","timezone":"Europe/London"}
                                """.formatted(email, password, label)))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        if (result.getResponse().getStatus() != 201) {
            throw new IllegalStateException("Could not register test user: " + body);
        }

        Cookie refreshCookie = result.getResponse().getCookie("patipp_refresh");
        return new TestUser(email, password, Json.readString(body, "accessToken"),
                Json.readString(body, "user.id"), refreshCookie);
    }

    protected record TestUser(String email, String password, String accessToken,
                              String id, Cookie refreshCookie) {

        public String bearer() {
            return "Bearer " + accessToken;
        }
    }
}
