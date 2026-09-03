package com.patipp.curriculum.internal;

import com.patipp.curriculum.api.CurriculumDtos.SuggestedTopic;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Curated starter topics, proposed when a subject is created.
 *
 * <p>A shipped catalogue rather than a live web search or an AI call, for three reasons.
 * Setting up a space is the moment a user is least willing to wait, and this answers
 * instantly. It is deterministic, so a curriculum - and therefore every mastery and
 * readiness number computed from it - does not depend on what a search returned that
 * afternoon. And it works with no network and no API key, which the architecture requires of
 * anything on a core path.
 *
 * <p>Phase 8 layers AI generation on top for subjects that are not listed here. That is the
 * correct division: the catalogue is the floor, AI is the enhancement.
 *
 * <p>Suggestions only. Nothing is written without the user accepting it - a curriculum they
 * did not choose would quietly distort the weak-topic detection built on top of it.
 */
@Component
public class TopicCatalogue {

    private static final Logger log = LoggerFactory.getLogger(TopicCatalogue.class);
    private static final String RESOURCE = "curriculum/topic-catalogue.json";

    /** Keyed by normalised subject name and by every normalised alias. */
    private final Map<String, Entry> byKey;

    public TopicCatalogue(ObjectMapper objectMapper) {
        this.byKey = load(objectMapper);
        log.info("Topic catalogue loaded: {} lookup keys", byKey.size());
    }

    private Map<String, Entry> load(ObjectMapper objectMapper) {
        Map<String, Entry> index = new LinkedHashMap<>();

        try (InputStream stream = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode root = objectMapper.readTree(stream);

            for (JsonNode subject : root.path("subjects")) {
                String name = subject.path("name").asString();

                List<SuggestedTopic> topics = new ArrayList<>();
                for (JsonNode topic : subject.path("topics")) {
                    topics.add(new SuggestedTopic(
                            topic.path("name").asString(),
                            topic.path("description").asString(null)));
                }
                if (topics.isEmpty()) {
                    continue;
                }

                Entry entry = new Entry(name, List.copyOf(topics));
                index.put(normalise(name), entry);
                for (JsonNode alias : subject.path("aliases")) {
                    // First definition wins, so an alias can never shadow a real subject name.
                    index.putIfAbsent(normalise(alias.asString()), entry);
                }
            }
        } catch (IOException | RuntimeException failure) {
            // A missing or malformed catalogue must not stop the application. Suggestions are
            // a convenience; manual entry still works, which is why this degrades rather than
            // throwing.
            log.error("Could not load {}; topic suggestions will be unavailable", RESOURCE, failure);
            return Map.of();
        }

        return Map.copyOf(index);
    }

    /** Looks up a subject by name or alias, case- and punctuation-insensitively. */
    public Optional<Entry> find(String subjectName) {
        if (subjectName == null || subjectName.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(byKey.get(normalise(subjectName)));
    }

    /**
     * Collapses everything that is not a letter or a digit and lower-cases the rest, so
     * "React Native", "react-native" and "ReactNative" are one key. Users type subject names
     * however they like, and a lookup that cared would miss most of the time.
     */
    private static String normalise(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    /**
     * @param canonicalName the catalogue's own spelling, shown so the user can see which
     *                      subject was matched when they typed an alias
     */
    public record Entry(String canonicalName, List<SuggestedTopic> topics) {
    }
}
