package com.patipp.learning.internal;

import com.patipp.preparations.api.BlueprintMerger;
import com.patipp.preparations.domain.PreparationSpace;
import com.patipp.preparations.domain.PreparationSpaceRepository;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * A space's merged blueprint, for the parts of the engine that need to read it.
 *
 * <p>Two lines, in one place, because otherwise every caller that wants a target retention or a
 * target success rate has to remember that the answer is the preparation type's blueprint with
 * the space's own config merged over it — and the one that forgets reads the type's defaults and
 * silently ignores the learner's overrides.
 *
 * <p><strong>No ownership check here, deliberately.</strong> An earlier version went through
 * {@code SpaceAccessGuard}, which made a rebuild depend on the <em>current user</em> being
 * signed in — even though a rebuild is given the user id it is rebuilding for. That is wrong on
 * its face: replaying user X's log is not an action by X, and it broke the moment anything
 * called it outside a request. Authorization belongs to the entry point, which does it.
 */
@Component
public class SpaceSettings {

    private final PreparationSpaceRepository spaces;
    private final BlueprintMerger merger;

    public SpaceSettings(PreparationSpaceRepository spaces, BlueprintMerger merger) {
        this.spaces = spaces;
        this.merger = merger;
    }

    /** An empty map for a space that no longer exists, so a rebuild degrades to the defaults. */
    @Transactional(readOnly = true)
    public Map<String, Object> settingsFor(UUID spaceId) {
        return spaces.findById(spaceId)
                .map(this::mergedFor)
                .orElseGet(Map::of);
    }

    private Map<String, Object> mergedFor(PreparationSpace space) {
        return merger.merge(space.preparationType().blueprint(), space.config());
    }
}
