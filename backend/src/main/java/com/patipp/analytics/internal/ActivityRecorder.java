package com.patipp.analytics.internal;

import com.patipp.analytics.domain.DailyActivity;
import com.patipp.analytics.domain.DailyActivityRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Counts what happened, by day.
 *
 * <p>Two rows per answer: one for the space and one for the cross-space roll-up. The roll-up is
 * not redundant — "have I studied at all today" is the question a streak answers, and computing it
 * by summing every space a learner has would make the cheapest read on the dashboard the most
 * expensive one.
 */
@Component
public class ActivityRecorder {

    private final DailyActivityRepository activity;

    public ActivityRecorder(DailyActivityRepository activity) {
        this.activity = activity;
    }

    @Transactional
    public void recordAnswer(UUID userId, UUID spaceId, boolean correct, Integer responseTimeMs,
                             Instant at) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);

        forSpace(userId, spaceId, day).recordAnswer(correct, responseTimeMs);
        forGlobal(userId, day).recordAnswer(correct, responseTimeMs);
    }

    @Transactional
    public void recordSessionCompleted(UUID userId, UUID spaceId, Instant at) {
        LocalDate day = LocalDate.ofInstant(at, ZoneOffset.UTC);

        forSpace(userId, spaceId, day).recordSessionCompleted();
        forGlobal(userId, day).recordSessionCompleted();
    }

    /** Consecutive days up to today with at least one answer, across every space. */
    @Transactional(readOnly = true)
    public Streak streak(UUID userId, Instant now) {
        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        List<DailyActivity> recent = activity.findGlobalSince(userId, today.minusDays(400));

        int current = 0;
        LocalDate expected = today;

        for (DailyActivity day : recent) {
            if (day.questionsAnswered() == 0) {
                continue;
            }
            if (day.activityDate().equals(expected)) {
                current++;
                expected = expected.minusDays(1);
            } else if (day.activityDate().equals(today) || day.activityDate().isAfter(expected)) {
                // Duplicate or out-of-order row for a day already counted; skip it rather than
                // breaking the run.
                continue;
            } else if (current == 0 && day.activityDate().equals(today.minusDays(1))) {
                // Nothing today yet. A streak should not read as broken until the day is over,
                // or opening the app at breakfast would tell you that you had lost it.
                current++;
                expected = day.activityDate().minusDays(1);
            } else {
                break;
            }
        }

        int longest = longestRun(recent);
        boolean answeredToday = recent.stream()
                .anyMatch(day -> day.activityDate().equals(today) && day.questionsAnswered() > 0);

        return new Streak(current, longest, answeredToday);
    }

    private static int longestRun(List<DailyActivity> daysNewestFirst) {
        int longest = 0;
        int run = 0;
        LocalDate previous = null;

        for (DailyActivity day : daysNewestFirst) {
            if (day.questionsAnswered() == 0) {
                continue;
            }
            if (previous != null && day.activityDate().equals(previous.minusDays(1))) {
                run++;
            } else {
                run = 1;
            }
            previous = day.activityDate();
            longest = Math.max(longest, run);
        }
        return longest;
    }

    /** One space's days, newest first, for the heatmap. */
    @Transactional(readOnly = true)
    public List<DailyActivity> since(UUID userId, UUID spaceId, LocalDate from) {
        return activity.findSpaceSince(userId, spaceId, from);
    }

    /** Clears one space's counts, so a rebuild can recompute them from the log. */
    @Transactional
    public void clear(UUID userId, UUID spaceId) {
        activity.deleteForSpace(userId, spaceId);
    }

    private DailyActivity forSpace(UUID userId, UUID spaceId, LocalDate day) {
        return activity.findSpaceDay(userId, spaceId, day)
                .orElseGet(() -> activity.save(DailyActivity.on(userId, spaceId, day)));
    }

    private DailyActivity forGlobal(UUID userId, LocalDate day) {
        return activity.findGlobalDay(userId, day)
                .orElseGet(() -> activity.save(DailyActivity.on(userId, null, day)));
    }

    /**
     * @param answeredToday false while today is still open, which is why a streak does not read
     *                      as broken before the day is over
     */
    public record Streak(int current, int longest, boolean answeredToday) {
    }
}
