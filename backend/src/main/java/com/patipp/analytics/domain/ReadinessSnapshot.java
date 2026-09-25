package com.patipp.analytics.domain;

import com.patipp.analytics.model.ReadinessResult;
import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One day's readiness figure, with everything needed to account for it.
 *
 * <p>Stored rather than recomputed on demand for one reason: a score that cannot say how it
 * changed since yesterday cannot be acted on, and "since yesterday" requires yesterday to have
 * been written down. The components, weights and drivers travel with it so a figure recorded in
 * March still explains itself in June, after the weights have changed.
 */
@Entity
@Table(name = "readiness_snapshots")
public class ReadinessSnapshot extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "preparation_space_id", nullable = false, updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "captured_on", nullable = false, updatable = false)
    private LocalDate capturedOn;

    @Column(name = "score", nullable = false)
    private BigDecimal score;

    @Column(name = "raw_score", nullable = false)
    private BigDecimal rawScore;

    @Column(name = "confidence", nullable = false)
    private BigDecimal confidence;

    @Column(name = "confidence_band", nullable = false, length = 12)
    private String confidenceBand;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "components", nullable = false)
    private Map<String, Object> components = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "weights", nullable = false)
    private Map<String, Object> weights = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deltas", nullable = false)
    private Map<String, Object> deltas = new LinkedHashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "drivers", nullable = false)
    private List<String> drivers = List.of();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "biggest_lever", nullable = false)
    private Map<String, Object> biggestLever = new LinkedHashMap<>();

    @Column(name = "model_version", nullable = false, length = 16)
    private String modelVersion;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReadinessSnapshot() {
    }

    public static ReadinessSnapshot of(UUID userId, UUID spaceId, LocalDate on,
                                       ReadinessResult result, Instant at) {
        ReadinessSnapshot snapshot = new ReadinessSnapshot();
        snapshot.id = UuidV7.generate();
        snapshot.userId = userId;
        snapshot.preparationSpaceId = spaceId;
        snapshot.capturedOn = on;
        snapshot.createdAt = at;
        snapshot.apply(result);
        return snapshot;
    }

    /** Rewrites today's snapshot in place, so the score moves through the day as work happens. */
    public void update(ReadinessResult result) {
        apply(result);
    }

    private void apply(ReadinessResult result) {
        this.score = scaled(result.score(), 2);
        this.rawScore = scaled(result.raw(), 2);
        this.confidence = scaled(result.confidence(), 3);
        this.confidenceBand = result.band().name();
        this.components = new LinkedHashMap<>(result.components());
        this.weights = new LinkedHashMap<>(result.weights());
        this.deltas = new LinkedHashMap<>(result.deltas());
        this.drivers = List.copyOf(result.drivers());

        Map<String, Object> lever = new LinkedHashMap<>();
        if (result.biggestLever() != null) {
            lever.put("component", result.biggestLever().component());
            lever.put("action", result.biggestLever().action());
            lever.put("estimatedGain", result.biggestLever().estimatedGain());
        }
        this.biggestLever = lever;
        this.modelVersion = result.version();
    }

    private static BigDecimal scaled(double value, int scale) {
        return BigDecimal.valueOf(Math.clamp(value, 0.0, scale == 3 ? 1.0 : 100.0))
                .setScale(scale, RoundingMode.HALF_UP);
    }

    /**
     * The component values, for computing tomorrow's deltas.
     *
     * <p>Includes {@code total} under the same key the model expects, so the next computation can
     * report a change in the overall score as well as in each part.
     */
    public Map<String, Double> componentsForDelta() {
        Map<String, Double> previous = new LinkedHashMap<>();
        components.forEach((key, value) -> {
            if (value instanceof Number number) {
                previous.put(key, number.doubleValue());
            }
        });
        previous.put("total", score.doubleValue());
        return previous;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID id() {
        return id;
    }

    public LocalDate capturedOn() {
        return capturedOn;
    }

    public double score() {
        return score.doubleValue();
    }

    public double rawScore() {
        return rawScore.doubleValue();
    }

    public double confidence() {
        return confidence.doubleValue();
    }

    public String confidenceBand() {
        return confidenceBand;
    }

    public Map<String, Object> components() {
        return Map.copyOf(components);
    }

    public Map<String, Object> weights() {
        return Map.copyOf(weights);
    }

    public Map<String, Object> deltas() {
        return Map.copyOf(deltas);
    }

    public List<String> drivers() {
        return List.copyOf(drivers);
    }

    public Map<String, Object> biggestLever() {
        return Map.copyOf(biggestLever);
    }

    public String modelVersion() {
        return modelVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
