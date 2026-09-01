package com.patipp.common.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-cutting beans that belong to no single feature module.
 */
@Configuration
public class CoreConfig {

    /**
     * Injected everywhere instead of calling {@code Instant.now()}.
     *
     * <p>Time is an input, and an input you cannot control is an input you cannot test. The
     * spaced-repetition work in Phase 6 depends on being able to advance the clock by three
     * weeks and assert what the scheduler does; that is impossible if the code reaches for
     * the system clock directly.
     */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
