package com.patipp;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A real PostgreSQL for integration tests.
 *
 * <p>Pinned to the same 16-alpine image as {@code infra/docker-compose.yml} rather than
 * {@code postgres:latest}. Tests that run against a different major version are testing a
 * database you do not deploy, and the differences that bite - jsonb behaviour, index
 * expressions, collation - are precisely the ones this schema leans on.
 *
 * <p>An in-memory database was never an option: partial unique indexes, composite foreign
 * keys, jsonb and CHECK constraints carry real correctness weight here, and H2 would
 * silently fail to enforce them. Spring's context caching starts this container once for
 * the whole run, so the cost is paid a single time.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"));
    }
}
