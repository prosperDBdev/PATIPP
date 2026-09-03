package com.patipp.curriculum.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CurriculumDtos {

    private CurriculumDtos() {
    }

    private static final String HEX_COLOR = "^#[0-9A-Fa-f]{6}$";

    public record CreateSubjectRequest(
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            @Pattern(regexp = HEX_COLOR, message = "must be a hex colour such as #3B82F6") String color,
            @DecimalMin("0.0") @DecimalMax("1000.0") BigDecimal weight) {
    }

    public record UpdateSubjectRequest(
            @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            @Pattern(regexp = HEX_COLOR, message = "must be a hex colour such as #3B82F6") String color,
            Short position,
            @DecimalMin("0.0") @DecimalMax("1000.0") BigDecimal weight) {
    }

    public record SubjectResponse(
            UUID id,
            String name,
            String description,
            String color,
            short position,
            BigDecimal weight,
            Instant createdAt,
            List<TopicResponse> topics) {
    }

    /**
     * @param parentTopicId null creates a top-level topic in the subject; otherwise the new
     *                      topic becomes a child, and its subject is inherited from the
     *                      parent rather than supplied, so the two can never disagree.
     */
    public record CreateTopicRequest(
            UUID subjectId,
            UUID parentTopicId,
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            @DecimalMin("0.0") @DecimalMax("1000.0") BigDecimal weight) {
    }

    public record UpdateTopicRequest(
            @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            Short position,
            @DecimalMin("0.0") @DecimalMax("1000.0") BigDecimal weight) {
    }

    /**
     * A proposed topic, not yet created. Suggestions the subject already has are filtered
     * out before they reach the client, so everything returned is genuinely addable.
     */
    public record SuggestedTopic(String name, String description) {
    }

    /**
     * @param matchedSubject the catalogue name that was matched, which may differ from what
     *                       the user typed if they used an alias like "js" or "react-native"
     * @param topics         suggestions that are not already present in the subject
     */
    public record TopicSuggestions(
            boolean matched,
            String matchedSubject,
            String source,
            List<SuggestedTopic> topics) {
    }

    /** Accepts a chosen subset of suggestions in one request. */
    public record BulkTopicRequest(
            @NotNull UUID subjectId,
            List<@Size(min = 1, max = 120) String> names) {
    }

    public record TopicResponse(
            UUID id,
            UUID subjectId,
            UUID parentTopicId,
            String name,
            String description,
            short position,
            BigDecimal weight,
            short depth,
            String path,
            List<TopicResponse> children) {
    }
}
