package com.patipp.sessions.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChallengeDtos {
    private ChallengeDtos() {}
    public record Brief(int day, String title, String subject, String topic, String core,
                        String stretch, List<String> checks, String resourceTitle, String resourceUrl,
                        String readingFocus, Integer revisitDay, Integer timedMinutes) {}
    public record Progress(int day, UUID questionId, boolean completed, boolean independent,
                           String assistance, int attempts) {}
    public record Activity(int day, Instant at, boolean completed, String assistance, String reflection) {}
    public record Guide(String key, List<Brief> days, List<Progress> progress, List<Activity> activity,
                        SessionDtos.SessionListEntry inProgress) {}
    public record Enrollment(UUID spaceId) {}
}
