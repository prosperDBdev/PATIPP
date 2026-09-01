package com.patipp.preparations.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No Spring, no database - this is the kind of test the whole architecture exists to make
 * possible for the adaptive engine later.
 */
class BlueprintMergerTest {

    private final BlueprintMerger merger = new BlueprintMerger();

    @Test
    @DisplayName("an empty override returns the blueprint untouched")
    void emptyOverrideChangesNothing() {
        Map<String, Object> blueprint = Map.of("scoringPolicy", "SIMPLE_CORRECTNESS");

        assertThat(merger.merge(blueprint, Map.of())).isEqualTo(blueprint);
        assertThat(merger.merge(blueprint, null)).isEqualTo(blueprint);
    }

    @Test
    @DisplayName("a top-level value is replaced")
    void topLevelValueIsReplaced() {
        Map<String, Object> result = merger.merge(
                Map.of("targetSuccessRate", 0.78, "schedulerPolicy", "FSRS_V1"),
                Map.of("targetSuccessRate", 0.6));

        assertThat(result).containsEntry("targetSuccessRate", 0.6);
        assertThat(result).containsEntry("schedulerPolicy", "FSRS_V1");
    }

    @Test
    @DisplayName("nested objects merge key by key instead of being replaced wholesale")
    void nestedObjectsMerge() {
        Map<String, Object> result = merger.merge(
                Map.of("defaults", Map.of(
                        "sessionLength", 20,
                        "examDurationMinutes", 60,
                        "immediateFeedback", true)),
                Map.of("defaults", Map.of("sessionLength", 5)));

        @SuppressWarnings("unchecked")
        Map<String, Object> defaults = (Map<String, Object>) result.get("defaults");

        assertThat(defaults).containsEntry("sessionLength", 5);
        // Overriding one default must not silently discard the others.
        assertThat(defaults).containsEntry("examDurationMinutes", 60);
        assertThat(defaults).containsEntry("immediateFeedback", true);
    }

    @Test
    @DisplayName("lists are replaced, not combined")
    void listsAreReplaced() {
        Map<String, Object> result = merger.merge(
                Map.of("allowedQuestionTypes", List.of("MCQ", "TRUE_FALSE", "CODING")),
                Map.of("allowedQuestionTypes", List.of("MCQ")));

        // A space that says it allows only MCQ means only MCQ. Merging would quietly
        // reinstate exactly the types it set out to exclude.
        assertThat(result.get("allowedQuestionTypes")).isEqualTo(List.of("MCQ"));
    }

    @Test
    @DisplayName("an override may introduce a key the blueprint never had")
    void overrideMayAddNewKeys() {
        Map<String, Object> result = merger.merge(
                Map.of("scoringPolicy", "SIMPLE_CORRECTNESS"),
                Map.of("customLabel", "My own setting"));

        assertThat(result).containsEntry("customLabel", "My own setting");
        assertThat(result).containsEntry("scoringPolicy", "SIMPLE_CORRECTNESS");
    }

    @Test
    @DisplayName("merging does not mutate either input")
    void inputsAreNotMutated() {
        Map<String, Object> blueprint = new java.util.HashMap<>(
                Map.of("defaults", new java.util.HashMap<>(Map.of("sessionLength", 20))));
        Map<String, Object> overrides = Map.of("defaults", Map.of("sessionLength", 5));

        merger.merge(blueprint, overrides);

        @SuppressWarnings("unchecked")
        Map<String, Object> originalDefaults = (Map<String, Object>) blueprint.get("defaults");
        assertThat(originalDefaults).containsEntry("sessionLength", 20);
    }
}
