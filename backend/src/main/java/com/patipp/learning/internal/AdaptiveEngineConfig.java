package com.patipp.learning.internal;

import com.patipp.adaptive.AbilityEstimator;
import com.patipp.adaptive.CompositeQuestionSelector;
import com.patipp.adaptive.DifficultyTargeter;
import com.patipp.adaptive.EloAbilityEstimator;
import com.patipp.adaptive.QuestionSelector;
import com.patipp.adaptive.WeaknessDetector;
import com.patipp.scheduling.Fsrs1Scheduler;
import com.patipp.scheduling.ReviewScheduler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the pure engine into the application.
 *
 * <p>The engine classes carry no Spring annotations - an ArchUnit rule fails the build if
 * they ever do - so something has to publish them as beans, and this is it. The indirection
 * is the point: the algorithm can be constructed in a unit test with {@code new}, and
 * swapping an implementation is a change to this one file.
 */
@Configuration
public class AdaptiveEngineConfig {

    @Bean
    AbilityEstimator abilityEstimator() {
        return new EloAbilityEstimator();
    }

    @Bean
    DifficultyTargeter difficultyTargeter() {
        return new DifficultyTargeter();
    }

    @Bean
    WeaknessDetector weaknessDetector() {
        return new WeaknessDetector();
    }

    @Bean
    QuestionSelector questionSelector(DifficultyTargeter targeter) {
        return new CompositeQuestionSelector(targeter);
    }

    /**
     * The spaced-repetition scheduler.
     *
     * <p>{@code FSRS_V1} is a deliberate simplification: full FSRS-5 fits seventeen parameters
     * against a large review history, which nobody has on day one. Swapping it later is a change
     * to this one line, and every scheduled date carries the version that chose it.
     */
    @Bean
    ReviewScheduler reviewScheduler() {
        return new Fsrs1Scheduler();
    }
}
