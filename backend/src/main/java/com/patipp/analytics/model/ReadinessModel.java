package com.patipp.analytics.model;

import com.patipp.analytics.model.ReadinessInputs.ComponentWeights;

/**
 * Computes a readiness score.
 *
 * <p>An interface with a {@link #version()}, like the other engines, and for the same reason:
 * every snapshot is stamped with the model that produced it, so a score recorded in March stays
 * interpretable after the weights change in June.
 */
public interface ReadinessModel {

    String version();

    /**
     * @param previous the last snapshot's components, for the deltas, or null when there is none
     */
    ReadinessResult compute(ReadinessInputs inputs, ComponentWeights weights,
                            java.util.Map<String, Double> previous);
}
