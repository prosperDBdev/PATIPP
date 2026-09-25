package com.patipp.analytics.domain;

import com.patipp.common.id.UuidV7;
import com.patipp.common.jpa.AssignedIdEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What one learner did on one day.
 *
 * <p>Powers streaks and the activity heatmap without scanning the attempt log, which is the one
 * table guaranteed to grow without limit. Derived, like everything else in layer three.
 *
 * <p>A null {@code preparationSpaceId} is the cross-space roll-up, so "did I study at all today"
 * is one row read rather than a sum over every space the learner has.
 */
@Entity
@Table(name = "daily_activity")
public class DailyActivity extends AssignedIdEntity<UUID> {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(name = "preparation_space_id", updatable = false)
    private UUID preparationSpaceId;

    @Column(name = "activity_date", nullable = false, updatable = false)
    private LocalDate activityDate;

    @Column(name = "questions_answered", nullable = false)
    private int questionsAnswered;

    @Column(name = "correct", nullable = false)
    private int correct;

    @Column(name = "study_time_ms", nullable = false)
    private long studyTimeMs;

    @Column(name = "sessions_completed", nullable = false)
    private int sessionsCompleted;

    @Column(name = "updated_at", nullable = false, insertable = false, updatable = false)
    private Instant updatedAt;

    protected DailyActivity() {
    }

    public static DailyActivity on(UUID userId, UUID spaceId, LocalDate date) {
        DailyActivity activity = new DailyActivity();
        activity.id = UuidV7.generate();
        activity.userId = userId;
        activity.preparationSpaceId = spaceId;
        activity.activityDate = date;
        return activity;
    }

    public void recordAnswer(boolean wasCorrect, Integer responseTimeMs) {
        this.questionsAnswered++;
        if (wasCorrect) {
            this.correct++;
        }
        if (responseTimeMs != null && responseTimeMs > 0) {
            this.studyTimeMs += responseTimeMs;
        }
    }

    public void recordSessionCompleted() {
        this.sessionsCompleted++;
    }

    public LocalDate activityDate() {
        return activityDate;
    }

    public int questionsAnswered() {
        return questionsAnswered;
    }

    public int correct() {
        return correct;
    }

    public long studyTimeMs() {
        return studyTimeMs;
    }

    public int sessionsCompleted() {
        return sessionsCompleted;
    }

    public UUID preparationSpaceId() {
        return preparationSpaceId;
    }

    @Override
    public UUID getId() {
        return id;
    }

    public UUID id() {
        return id;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
