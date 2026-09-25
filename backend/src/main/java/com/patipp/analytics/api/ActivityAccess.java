package com.patipp.analytics.api;

import com.patipp.analytics.internal.ActivityRecorder;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * What analytics publishes for other modules to report into.
 *
 * <p>Deliberately write-only from the session engine's point of view. {@code sessions} tells
 * analytics that something happened; it never asks analytics anything, so a change to how activity
 * is counted cannot break answering a question.
 */
@Component
public class ActivityAccess {

    private final ActivityRecorder recorder;

    public ActivityAccess(ActivityRecorder recorder) {
        this.recorder = recorder;
    }

    public void answered(UUID userId, UUID spaceId, boolean correct, Integer responseTimeMs,
                         Instant at) {
        recorder.recordAnswer(userId, spaceId, correct, responseTimeMs, at);
    }

    public void sessionCompleted(UUID userId, UUID spaceId, Instant at) {
        recorder.recordSessionCompleted(userId, spaceId, at);
    }

    /** Clears one space's counts so a rebuild can recompute them from the attempt log. */
    public void clear(UUID userId, UUID spaceId) {
        recorder.clear(userId, spaceId);
    }
}
