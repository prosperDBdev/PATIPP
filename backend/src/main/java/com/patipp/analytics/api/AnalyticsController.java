package com.patipp.analytics.api;

import com.patipp.analytics.domain.DailyActivity;
import com.patipp.analytics.domain.ReadinessSnapshot;
import com.patipp.analytics.internal.ActivityRecorder;
import com.patipp.analytics.internal.ReadinessService;
import com.patipp.analytics.model.ReadinessResult;
import com.patipp.common.security.CurrentUser;
import com.patipp.preparations.api.SpaceAccessGuard;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The daily answer to "what should I do now, and am I actually ready?"
 *
 * <p>Every response carries its own breakdown. A bare percentage cannot say why it moved, so it
 * cannot be acted on, and a number nobody can act on is one they stop reading.
 */
@RestController
@RequestMapping("/api/v1/spaces/{spaceId}")
public class AnalyticsController {

    /** How far back the heatmap reaches by default. */
    private static final int HEATMAP_DAYS = 120;

    private final ReadinessService readiness;
    private final ActivityRecorder activity;
    private final SpaceAccessGuard accessGuard;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AnalyticsController(ReadinessService readiness, ActivityRecorder activity,
                              SpaceAccessGuard accessGuard, CurrentUser currentUser,
                              Clock clock) {
        this.readiness = readiness;
        this.activity = activity;
        this.accessGuard = accessGuard;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /**
     * Readiness now, recorded as today's snapshot.
     *
     * <p>A GET that writes, deliberately: the snapshot is what makes tomorrow's "since yesterday"
     * possible, and requiring a separate POST would mean the history only existed for learners who
     * happened to press something. Today's row is rewritten rather than appended, so repeated
     * reads do not inflate the history.
     */
    @GetMapping("/readiness")
    public ReadinessResponse readiness(@PathVariable UUID spaceId) {
        accessGuard.requireOwned(spaceId);
        UUID userId = currentUser.requireId();

        ReadinessResult result = readiness.computeAndRecord(userId, spaceId);
        ActivityRecorder.Streak streak = activity.streak(userId, clock.instant());

        return new ReadinessResponse(
                Math.round(result.score() * 10) / 10.0,
                Math.round(result.raw() * 10) / 10.0,
                result.confidence(),
                result.band().name(),
                result.headline(),
                result.components(),
                result.weights(),
                result.deltas(),
                result.drivers(),
                result.biggestLever() == null ? null : new LeverResponse(
                        result.biggestLever().component(),
                        result.biggestLever().action(),
                        result.biggestLever().estimatedGain()),
                new StreakResponse(streak.current(), streak.longest(), streak.answeredToday()),
                result.version());
    }

    /** The score over time, newest first, for the trend chart. */
    @GetMapping("/readiness/history")
    public List<HistoryPoint> history(@PathVariable UUID spaceId,
                                      @RequestParam(defaultValue = "60") int days) {
        accessGuard.requireOwned(spaceId);

        List<HistoryPoint> points = new ArrayList<>();
        for (ReadinessSnapshot snapshot : readiness.history(currentUser.requireId(), spaceId, days)) {
            points.add(new HistoryPoint(
                    snapshot.capturedOn().toString(),
                    snapshot.score(),
                    snapshot.confidenceBand(),
                    snapshot.components()));
        }
        return points;
    }

    /**
     * Days studied, for the heatmap and the streak.
     *
     * <p>Only days with something on them. Sending a row per empty day would be mostly zeroes,
     * and the client can fill the gaps far more cheaply than the wire can carry them.
     */
    @GetMapping("/activity")
    public ActivityResponse activity(@PathVariable UUID spaceId,
                                     @RequestParam(defaultValue = "120") int days) {
        accessGuard.requireOwned(spaceId);
        UUID userId = currentUser.requireId();

        LocalDate today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
        LocalDate from = today.minusDays(Math.clamp(days, 7, 400) - 1);

        List<DayResponse> byDay = new ArrayList<>();
        for (DailyActivity day : activity.since(userId, spaceId, from)) {
            if (day.questionsAnswered() == 0 && day.sessionsCompleted() == 0) {
                continue;
            }
            byDay.add(new DayResponse(
                    day.activityDate().toString(),
                    day.questionsAnswered(),
                    day.correct(),
                    Math.round(day.studyTimeMs() / 60_000.0),
                    day.sessionsCompleted()));
        }

        ActivityRecorder.Streak streak = activity.streak(userId, clock.instant());
        return new ActivityResponse(
                from.toString(), today.toString(),
                new StreakResponse(streak.current(), streak.longest(), streak.answeredToday()),
                byDay);
    }

    /**
     * @param raw        before the confidence factor; the gap between this and the score is the
     *                   explanation for an early figure that looks unfairly low
     * @param components each of the six, 0-100
     * @param weights    what each was multiplied by, so the score can be checked by hand
     * @param drivers    the changes worth reading, largest first
     */
    public record ReadinessResponse(
            double score,
            double raw,
            double confidence,
            String confidenceBand,
            String headline,
            Map<String, Double> components,
            Map<String, Double> weights,
            Map<String, Double> deltas,
            List<String> drivers,
            LeverResponse biggestLever,
            StreakResponse streak,
            String modelVersion) {
    }

    /** @param estimatedGain points of readiness this is roughly worth */
    public record LeverResponse(String component, String action, double estimatedGain) {
    }

    public record StreakResponse(int current, int longest, boolean answeredToday) {
    }

    public record HistoryPoint(String on, double score, String confidenceBand,
                               Map<String, Object> components) {
    }

    /** @param studyMinutes rounded; nobody needs study time to the millisecond */
    public record DayResponse(String on, int answered, int correct, long studyMinutes,
                              int sessions) {
    }

    public record ActivityResponse(String from, String to, StreakResponse streak,
                                   List<DayResponse> days) {
    }
}
