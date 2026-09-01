package com.patipp.preparations.api;

import com.patipp.preparations.api.PreparationDtos.CreateSpaceRequest;
import com.patipp.preparations.api.PreparationDtos.SpaceResponse;
import com.patipp.preparations.api.PreparationDtos.UpdateSpaceRequest;
import com.patipp.preparations.internal.PreparationSpaceService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/spaces")
public class PreparationSpaceController {

    private final PreparationSpaceService spaceService;

    public PreparationSpaceController(PreparationSpaceService spaceService) {
        this.spaceService = spaceService;
    }

    @GetMapping
    public List<SpaceResponse> list(
            @RequestParam(name = "includeArchived", defaultValue = "false") boolean includeArchived) {
        return spaceService.list(includeArchived);
    }

    @GetMapping("/{spaceId}")
    public SpaceResponse get(@PathVariable UUID spaceId) {
        return spaceService.get(spaceId);
    }

    @PostMapping
    public ResponseEntity<SpaceResponse> create(@Valid @RequestBody CreateSpaceRequest request) {
        SpaceResponse created = spaceService.create(request);
        return ResponseEntity.created(URI.create("/api/v1/spaces/" + created.id())).body(created);
    }

    @PatchMapping("/{spaceId}")
    public SpaceResponse update(@PathVariable UUID spaceId,
                                @Valid @RequestBody UpdateSpaceRequest request) {
        return spaceService.update(spaceId, request);
    }

    /**
     * Archives the space. This is DELETE because that is what a client means, but the space
     * is only soft-deleted: sessions, attempts and mastery will reference it, and archiving
     * is not a request to destroy months of study history.
     */
    @DeleteMapping("/{spaceId}")
    public ResponseEntity<Void> archive(@PathVariable UUID spaceId) {
        spaceService.archive(spaceId);
        return ResponseEntity.noContent().build();
    }
}
