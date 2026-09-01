package com.patipp.preparations.api;

import com.patipp.common.security.CurrentUser;
import com.patipp.preparations.api.PreparationDtos.PreparationTypeResponse;
import com.patipp.preparations.domain.PreparationTypeRepository;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes the preparation types a user may build a space from.
 *
 * <p>Read-only for now. Creating custom types is supported by the schema but has no
 * endpoint yet: authoring a blueprint by hand needs a real editor to be useful, and that
 * belongs with the question-type work in Phase 2 rather than shipped as a raw JSON field.
 */
@RestController
@RequestMapping("/api/v1/preparation-types")
public class PreparationTypeController {

    private final PreparationTypeRepository types;
    private final CurrentUser currentUser;

    public PreparationTypeController(PreparationTypeRepository types, CurrentUser currentUser) {
        this.types = types;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public List<PreparationTypeResponse> list() {
        return types.findAvailableTo(currentUser.requireId()).stream()
                .map(type -> new PreparationTypeResponse(
                        type.id(), type.key(), type.name(), type.description(),
                        type.icon(), type.isSystem(), type.blueprint()))
                .toList();
    }
}
