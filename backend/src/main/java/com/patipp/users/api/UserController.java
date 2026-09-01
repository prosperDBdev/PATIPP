package com.patipp.users.api;

import com.patipp.auth.domain.User;
import com.patipp.auth.domain.UserRepository;
import com.patipp.common.error.NotFoundException;
import com.patipp.common.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository users;
    private final CurrentUser currentUser;

    public UserController(UserRepository users, CurrentUser currentUser) {
        this.users = users;
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    @Transactional(readOnly = true)
    public ProfileResponse me() {
        return toResponse(requireCurrentUser());
    }

    @PatchMapping("/me")
    @Transactional
    public ProfileResponse updateMe(@Valid @RequestBody UpdateProfileRequest request) {
        User user = requireCurrentUser();

        if (request.displayName() != null) {
            user.rename(request.displayName());
        }
        if (request.timezone() != null) {
            user.changeTimezone(request.timezone());
        }
        if (request.settings() != null) {
            user.replaceSettings(request.settings());
        }
        return toResponse(user);
    }

    private User requireCurrentUser() {
        UUID userId = currentUser.requireId();
        return users.findById(userId)
                .orElseThrow(() -> new NotFoundException("user.not_found", "No such user."));
    }

    private ProfileResponse toResponse(User user) {
        return new ProfileResponse(user.id(), user.email(), user.displayName(),
                user.timezone(), user.settings(), user.createdAt(), user.lastActiveAt());
    }

    /**
     * @param settings client-owned preferences (theme, daily goal, shortcuts). Stored as
     *                 an opaque JSON object because the server never reads it - giving each
     *                 preference a column would mean a migration every time the UI grows a
     *                 toggle.
     */
    public record UpdateProfileRequest(
            @Size(min = 1, max = 80) String displayName,
            @Size(min = 1, max = 64) String timezone,
            Map<String, Object> settings) {
    }

    public record ProfileResponse(
            UUID id,
            String email,
            String displayName,
            String timezone,
            Map<String, Object> settings,
            Instant createdAt,
            Instant lastActiveAt) {
    }
}
