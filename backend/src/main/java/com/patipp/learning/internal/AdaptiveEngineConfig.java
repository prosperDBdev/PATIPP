package com.patipp.learning.internal;

import com.patipp.adaptive.AbilityEstimator;
import com.patipp.adaptive.CompositeQuestionSelector;
import com.patipp.adaptive.DifficultyTargeter;
import com.patipp.adaptive.EloAbilityEstimator;
import com.patipp.adaptive.QuestionSelector;
import com.patipp.adaptive.WeaknessDetector;
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
}
