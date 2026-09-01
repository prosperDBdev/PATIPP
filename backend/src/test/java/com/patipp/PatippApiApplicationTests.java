package com.patipp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Boots the whole application against a real PostgreSQL.
 *
 * <p>Worth more than it looks. Because {@code ddl-auto} is {@code validate}, this test fails
 * whenever an entity and its Flyway migration disagree - so a mismatched column type is
 * caught here rather than at startup in production.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
class PatippApiApplicationTests {

    @Test
    @DisplayName("the context starts and the schema matches the entities")
    void contextLoads() {
    }
}
