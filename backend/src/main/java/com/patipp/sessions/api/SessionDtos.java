package com.patipp.sessions.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class SessionDtos {

    private SessionDtos() {
    }

    /**
     * @param mode   defaults to PRACTICE
     * @param length null falls back to the preparation type's default rather than a number
     *               written into the code
     */
    public record StartSessionRequest(
            String mode,
            @Min(1) @Max(100) Integer length,
            List<UUID> subjectIds,
            List<UUID> topicIds,
            List<String> types,
            List<String> difficulties) {
    }

    /**
     * @param answer          shape depends on the question format: {@code optionIds} for
     *                        choice questions, {@code value} for true/false, {@code text} for
     *                        short answer, {@code grade} for flashcards
     * @param responseTimeMs  measured by the client. Used for pacing feedback and, from
     *                        Phase 6, to tell recall apart from working it out.
     * @param confidence      optional 1-4 self-report
     * @param clientAttemptId optional idempotency key for offline replay in Phase 9
     */
    public record SubmitAnswerRequest(
            @NotNull Integer position,
            @NotNull Map<String, Object> answer,
            @Min(0) Integer responseTimeMs,
            @Min(1) @Max(4) Integer confidence,
            UUID clientAttemptId) {
    }

    /** The question as the learner sees it: no answer key, no explanation. */
    public record ServedItem(
            int position,
            UUID questionId,
            String type,
            String difficulty,
            UUID subjectId,
            UUID topicId,
            int estimatedSeconds,
            String stem,
            List<String> hints,
            /**
             * The payload with anything that gives the answer away removed. Sending the full
             * payload and hiding it in the UI would put the answer key in the browser, where
             * anyone can read it.
             */
            Map<String, Object> presentation) {
    }

    public record SessionResponse(
            UUID id,
            String mode,
            String status,
            Instant startedAt,
            Instant deadlineAt,
            Instant submittedAt,
            int totalItems,
            int answeredCount,
            int correctCount,
            long activeMs,
            boolean immediateFeedback,
            /** Null once every question has been answered. */
            ServedItem currentItem) {
    }

    /**
     * @param explanation only present when the mode reveals feedback immediately; an exam
     *                    returns it in the summary instead
     */
    public record AnswerResult(
            int position,
            boolean correct,
            double score,
            String note,
            String explanation,
            Map<String, Object> correctAnswer,
            int answeredCount,
            int totalItems,
            boolean sessionComplete,
            ServedItem nextItem) {
    }

    public record SessionSummary(
            UUID id,
            String mode,
            String status,
            Instant startedAt,
            Instant submittedAt,
            long activeMs,
            int totalItems,
            int answeredCount,
            int correctCount,
            Double score,
            Map<String, Object> breakdown,
            List<ReviewItem> items) {
    }

    /** One line of the post-session review, with everything now revealed. */
    public record ReviewItem(
            int position,
            UUID questionId,
            String type,
            String difficulty,
            String stem,
            boolean correct,
            double score,
            String note,
            String explanation,
            Map<String, Object> yourAnswer,
            Map<String, Object> correctAnswer,
            int timeSpentMs,
            Map<String, Object> selectionReason) {
    }

    public record SessionListEntry(
            UUID id,
            String mode,
            String status,
            Instant startedAt,
            Instant submittedAt,
            int totalItems,
            int answeredCount,
            int correctCount,
            Double score) {
    }

    /** What is available to practise right now, shown before a session is started. */
    public record SessionAvailability(int availableQuestions, int suggestedLength) {
    }
}
