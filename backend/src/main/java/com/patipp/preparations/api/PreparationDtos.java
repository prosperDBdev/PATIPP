package com.patipp.preparations.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

public final class PreparationDtos {

    private PreparationDtos() {
    }

    public record CreateSpaceRequest(
            @NotNull UUID preparationTypeId,
            @NotBlank @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            LocalDate targetDate,
            @Min(0) @Max(100) Short targetScore,
            Map<String, Object> config) {
    }

    /**
     * Every field is optional: absent means "leave unchanged". This is a PATCH, and a PATCH
     * that requires the whole object is a PUT wearing a disguise - it would make a client
     * that only wants to move the exam date responsible for faithfully echoing back every
     * other field, which is exactly how fields get silently reset.
     */
    public record UpdateSpaceRequest(
            @Size(min = 1, max = 120) String name,
            @Size(max = 2000) String description,
            LocalDate targetDate,
            @Min(0) @Max(100) Short targetScore,
            String status,
            Map<String, Object> config) {
    }

    public record SpaceResponse(
            UUID id,
            String name,
            String description,
            String status,
            LocalDate targetDate,
            Short targetScore,
            Integer daysUntilTarget,
            PreparationTypeResponse preparationType,
            Map<String, Object> config,
            /**
             * The type blueprint with the space's own config merged over it. The client reads
             * this rather than the raw blueprint, so a space-level override behaves identically
             * to a type-level default and no caller has to know which layer a value came from.
             */
            Map<String, Object> effectiveSettings,
            Instant createdAt,
            Instant updatedAt,
            Instant archivedAt) {
    }

    public record PreparationTypeResponse(
            UUID id,
            String key,
            String name,
            String description,
            String icon,
            boolean system,
            Map<String, Object> blueprint) {
    }
}
