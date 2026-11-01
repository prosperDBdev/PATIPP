package com.patipp.preparations.api;

import com.patipp.preparations.domain.PreparationTypeRepository;
import com.patipp.preparations.internal.PreparationSpaceService;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Creates an ordinary preparation space for an authored learning guide. */
@Component
public class ChallengeSpaceAccess {
    private final PreparationSpaceService spaces;
    private final PreparationTypeRepository types;

    public ChallengeSpaceAccess(PreparationSpaceService spaces, PreparationTypeRepository types) {
        this.spaces = spaces;
        this.types = types;
    }

    public UUID findOrCreate(String key, String title) {
        return spaces.list(false).stream()
                .filter(space -> key.equals(space.config().get("challengeKey")))
                .map(PreparationDtos.SpaceResponse::id).findFirst()
                .orElseGet(() -> spaces.create(new PreparationDtos.CreateSpaceRequest(
                        types.findByKey("CODING_TEST").orElseThrow().id(), title,
                        "Daily IDE practice: write, test, explain and rebuild. Resume whenever you miss a day.",
                        null, null, Map.of("challengeKey", key))).id());
    }
}
