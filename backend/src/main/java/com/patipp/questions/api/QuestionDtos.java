package com.patipp.questions.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuestionDtos {

    private QuestionDtos() {
    }

    /**
     * @param payload the format-specific body. Deliberately an untyped map here: it is
     *                validated against the rules of {@code type} by the domain, which
     *                produces far better messages than bean validation could, and keeps the
     *                set of formats out of the DTO layer entirely.
     */
    public record CreateQuestionRequest(
            @NotNull UUID subjectId,
            UUID topicId,
            @NotBlank String type,
            @NotBlank String difficulty,
            @NotBlank @Size(min = 1, max = 8000) String stem,
            @Size(max = 8000) String explanation,
            List<@Size(max = 500) String> hints,
            @NotNull Map<String, Object> payload,
            @Min(5) @Max(7200) Integer estimatedSeconds,
            List<@Size(max = 40) String> tags) {
    }

    /** All fields optional; absent means unchanged. */
    public record UpdateQuestionRequest(
            UUID subjectId,
            UUID topicId,
            String difficulty,
            String status,
            @Size(min = 1, max = 8000) String stem,
            @Size(max = 8000) String explanation,
            List<@Size(max = 500) String> hints,
            Map<String, Object> payload,
            @Min(5) @Max(7200) Integer estimatedSeconds,
            List<@Size(max = 40) String> tags) {
    }

    public record QuestionResponse(
            UUID id,
            UUID subjectId,
            UUID topicId,
            String type,
            String difficulty,
            String status,
            String source,
            int estimatedSeconds,
            List<String> tags,
            int version,
            String stem,
            String explanation,
            List<String> hints,
            Map<String, Object> payload,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** A row in the question list. Omits the payload, which is only needed when editing. */
    public record QuestionSummary(
            UUID id,
            UUID subjectId,
            UUID topicId,
            String type,
            String difficulty,
            String status,
            List<String> tags,
            String stem,
            int estimatedSeconds,
            Instant createdAt) {
    }

    public record QuestionPage(
            List<QuestionSummary> items,
            int page,
            int size,
            long totalItems,
            int totalPages) {
    }

    public record VersionSummary(
            UUID id,
            int version,
            String stem,
            Instant createdAt,
            boolean current) {
    }

    // ------------------------------------------------------------------ import

    /**
     * @param format  {@code JSON} or {@code CSV}
     * @param content the raw file contents, pasted or uploaded
     * @param dryRun  when true, nothing is written. The default is true, so the destructive
     *                behaviour has to be asked for explicitly rather than being what you get
     *                by forgetting a field.
     */
    public record ImportRequest(
            @NotBlank String format,
            @NotBlank @Size(max = 4_000_000) String content,
            UUID defaultSubjectId,
            UUID defaultTopicId,
            Boolean dryRun) {

        public boolean isDryRun() {
            return dryRun == null || dryRun;
        }
    }

    /**
     * @param rows one entry per parsed record, in file order, so an author can map a problem
     *             back to the line that caused it
     */
    public record ImportReport(
            boolean dryRun,
            int totalRows,
            int valid,
            int duplicates,
            int invalid,
            int imported,
            List<ImportRow> rows) {
    }

    public record ImportRow(
            int line,
            String outcome,
            String stem,
            String type,
            List<FieldProblem> problems,
            UUID questionId) {
    }

    public record FieldProblem(String field, String message) {
    }
}
