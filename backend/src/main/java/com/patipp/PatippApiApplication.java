package com.patipp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * PATIPP - Personalized Adaptive Test and Interview Preparation Platform.
 *
 * <p>A modular monolith. Module boundaries live in the package structure under
 * {@code com.patipp} and are enforced by ArchUnit tests rather than by convention alone;
 * see {@code docs/ARCHITECTURE.md} section 4.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableJpaAuditing
public class PatippApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(PatippApiApplication.class, args);
    }
}
