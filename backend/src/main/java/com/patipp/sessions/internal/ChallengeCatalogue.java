package com.patipp.sessions.internal;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Authored content, independent of learner progress. Reference snippets never leave this layer in a brief. */
@Component
public class ChallengeCatalogue {
    public static final String KEY = "java-spring-30-v1";
    private final List<Day> days;

    public ChallengeCatalogue(ObjectMapper mapper) {
        try (var stream = new ClassPathResource("challenges/java-spring-30.json").getInputStream()) {
            days = List.copyOf(Arrays.asList(mapper.readValue(stream, Day[].class)));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read the Java/Spring challenge", failure);
        }
        if (days.size() != 30 || java.util.stream.IntStream.rangeClosed(1, 30)
                .anyMatch(day -> days.get(day - 1).day() != day)) {
            throw new IllegalStateException("Challenge must contain days 1 through 30 in order");
        }
    }

    public List<Day> days() { return days; }

    public record Day(int day, String title, String subject, String topic, String core,
                      String stretch, List<String> checks, List<String> hints,
                      String referenceSolution, String resourceTitle, String resourceUrl,
                      String readingFocus, Integer revisitDay, Integer timedMinutes) {
        public String stem() {
            return "Day " + day + ": " + title + "\n\n10–30 minute core:\n" + core
                    + "\n\nExpected behaviour (check locally):\n- " + String.join("\n- ", checks)
                    + "\n\n60–120 minute extension (optional):\n" + stretch
                    + "\n\nExplain your decisions out loud. Record incomplete work honestly and resume later.";
        }
    }
}
