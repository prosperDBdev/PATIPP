package com.patipp.preparations.api;

import com.patipp.common.error.NotFoundException;
import com.patipp.common.security.CurrentUser;
import com.patipp.preparations.domain.PreparationSpace;
import com.patipp.preparations.domain.PreparationSpaceRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single gate through which every other module reaches a preparation space.
 *
 * <p>Published from {@code preparations.api} because {@code curriculum}, and later
 * {@code questions}, {@code sessions} and the rest, all need to resolve a space id from a
 * URL into a space the caller is actually allowed to touch. Concentrating that in one place
 * means the ownership check is written once and cannot be forgotten in the twelfth module.
 *
 * <p>A space that does not exist and a space belonging to somebody else both raise
 * {@link NotFoundException}. Returning 403 for the second case would confirm that the id is
 * real, which turns the endpoint into an oracle for enumerating other users' spaces.
 */
@Component
public class SpaceAccessGuard {

    private final PreparationSpaceRepository spaces;
    private final CurrentUser currentUser;

    public SpaceAccessGuard(PreparationSpaceRepository spaces, CurrentUser currentUser) {
        this.spaces = spaces;
        this.currentUser = currentUser;
    }

    /** Resolves the space, asserting the authenticated caller owns it. */
    @Transactional(readOnly = true)
    public PreparationSpace requireOwned(UUID spaceId) {
        UUID userId = currentUser.requireId();
        return spaces.findOwned(spaceId, userId)
                .orElseThrow(() -> new NotFoundException(
                        "space.not_found", "No such preparation space."));
    }

    /**
     * As {@link #requireOwned} but also rejects archived spaces. Used by every write path:
     * an archived space is readable history, not a place to add new material.
     */
    @Transactional(readOnly = true)
    public PreparationSpace requireWritable(UUID spaceId) {
        PreparationSpace space = requireOwned(spaceId);
        if (space.isArchived()) {
            throw new NotFoundException("space.archived", "That preparation space is archived.");
        }
        return space;
    }

    /** Asserts ownership without materialising the entity. */
    @Transactional(readOnly = true)
    public UUID requireOwnedId(UUID spaceId) {
        return requireOwned(spaceId).id();
    }
}
