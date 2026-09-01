package com.patipp.preparations.internal;

import com.patipp.common.error.BadRequestException;
import com.patipp.common.error.ConflictException;
import com.patipp.common.error.NotFoundException;
import com.patipp.common.security.CurrentUser;
import com.patipp.preparations.api.PreparationDtos.CreateSpaceRequest;
import com.patipp.preparations.api.PreparationDtos.PreparationTypeResponse;
import com.patipp.preparations.api.PreparationDtos.SpaceResponse;
import com.patipp.preparations.api.PreparationDtos.UpdateSpaceRequest;
import com.patipp.preparations.api.SpaceAccessGuard;
import com.patipp.preparations.domain.PreparationSpace;
import com.patipp.preparations.domain.PreparationSpaceRepository;
import com.patipp.preparations.domain.PreparationType;
import com.patipp.preparations.domain.PreparationTypeRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PreparationSpaceService {

    private static final Set<String> ASSIGNABLE_STATUSES = Set.of(
            PreparationSpace.STATUS_ACTIVE,
            PreparationSpace.STATUS_PAUSED,
            PreparationSpace.STATUS_COMPLETED);

    private final PreparationSpaceRepository spaces;
    private final PreparationTypeRepository types;
    private final SpaceAccessGuard accessGuard;
    private final BlueprintMerger blueprintMerger;
    private final CurrentUser currentUser;
    private final Clock clock;

    public PreparationSpaceService(PreparationSpaceRepository spaces,
                                   PreparationTypeRepository types,
                                   SpaceAccessGuard accessGuard,
                                   BlueprintMerger blueprintMerger,
                                   CurrentUser currentUser,
                                   Clock clock) {
        this.spaces = spaces;
        this.types = types;
        this.accessGuard = accessGuard;
        this.blueprintMerger = blueprintMerger;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<SpaceResponse> list(boolean includeArchived) {
        UUID userId = currentUser.requireId();
        List<PreparationSpace> found = includeArchived
                ? spaces.findAllForUser(userId)
                : spaces.findActiveForUser(userId);
        return found.stream().map(this::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public SpaceResponse get(UUID spaceId) {
        return toResponse(accessGuard.requireOwned(spaceId));
    }

    @Transactional
    public SpaceResponse create(CreateSpaceRequest request) {
        UUID userId = currentUser.requireId();

        PreparationType type = types.findUsableBy(request.preparationTypeId(), userId)
                .orElseThrow(() -> new NotFoundException(
                        "preparation_type.not_found", "No such preparation type."));

        String name = request.name().strip();
        if (spaces.existsLiveWithName(userId, name)) {
            throw new ConflictException("space.name_taken",
                    "You already have a preparation space called \"" + name + "\".");
        }
        rejectPastTargetDate(request.targetDate());

        PreparationSpace space = PreparationSpace.create(
                userId, type, name, request.description(),
                request.targetDate(), request.targetScore(), request.config());

        return toResponse(spaces.save(space));
    }

    @Transactional
    public SpaceResponse update(UUID spaceId, UpdateSpaceRequest request) {
        PreparationSpace space = accessGuard.requireWritable(spaceId);
        UUID userId = currentUser.requireId();

        if (request.name() != null) {
            String name = request.name().strip();
            if (spaces.existsOtherLiveWithName(userId, name, spaceId)) {
                throw new ConflictException("space.name_taken",
                        "You already have a preparation space called \"" + name + "\".");
            }
            space.rename(name);
        }
        if (request.description() != null) {
            space.describe(request.description());
        }
        if (request.targetDate() != null || request.targetScore() != null) {
            rejectPastTargetDate(request.targetDate());
            space.retarget(
                    request.targetDate() != null ? request.targetDate() : space.targetDate(),
                    request.targetScore() != null ? request.targetScore() : space.targetScore());
        }
        if (request.status() != null) {
            if (!ASSIGNABLE_STATUSES.contains(request.status())) {
                // ARCHIVED is deliberately excluded: archiving is a distinct operation with
                // its own endpoint, because it also stamps archived_at and takes the space
                // out of every listing. Allowing it here would let a status edit do two
                // things at once.
                throw new BadRequestException("space.status_invalid",
                        "Status must be one of ACTIVE, PAUSED or COMPLETED.");
            }
            space.changeStatus(request.status());
        }
        if (request.config() != null) {
            space.replaceConfig(request.config());
        }

        return toResponse(space);
    }

    @Transactional
    public void archive(UUID spaceId) {
        accessGuard.requireOwned(spaceId).archive(clock.instant());
    }

    private void rejectPastTargetDate(LocalDate targetDate) {
        if (targetDate != null && targetDate.isBefore(LocalDate.now(clock))) {
            throw new BadRequestException("space.target_date_past",
                    "The target date cannot be in the past.");
        }
    }

    private SpaceResponse toResponse(PreparationSpace space) {
        PreparationType type = space.preparationType();

        Integer daysUntilTarget = space.targetDate() == null ? null
                : (int) ChronoUnit.DAYS.between(LocalDate.now(clock), space.targetDate());

        return new SpaceResponse(
                space.id(),
                space.name(),
                space.description(),
                space.status(),
                space.targetDate(),
                space.targetScore(),
                daysUntilTarget,
                toTypeResponse(type),
                space.config(),
                blueprintMerger.merge(type.blueprint(), space.config()),
                space.createdAt(),
                space.updatedAt(),
                space.archivedAt());
    }

    static PreparationTypeResponse toTypeResponse(PreparationType type) {
        return new PreparationTypeResponse(
                type.id(), type.key(), type.name(), type.description(),
                type.icon(), type.isSystem(), type.blueprint());
    }
}
