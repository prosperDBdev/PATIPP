package com.patipp.analytics.internal;

import com.patipp.analytics.model.ReadinessModel;
import com.patipp.analytics.model.WeightedReadinessModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishes the pure readiness model as a bean.
 *
 * <p>The model carries no Spring annotations — an ArchUnit rule fails the build if it ever does —
 * so something has to wire it. Swapping the weighting scheme is a change to this one line, and
 * every snapshot records which version produced it.
 */
@Configuration
public class ReadinessModelConfig {

    @Bean
    ReadinessModel readinessModel() {
        return new WeightedReadinessModel();
    }
}
