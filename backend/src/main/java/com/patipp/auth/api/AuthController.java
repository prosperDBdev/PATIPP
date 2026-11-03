package com.patipp.auth.api;

import com.patipp.auth.api.AuthDtos.AuthResponse;
import com.patipp.auth.api.AuthDtos.GoogleSignInRequest;
import com.patipp.auth.api.AuthDtos.LoginRequest;
import com.patipp.auth.api.AuthDtos.RegisterRequest;
import com.patipp.auth.internal.AuthService;
import com.patipp.auth.internal.GoogleAuthProperties;
import com.patipp.auth.internal.RefreshCookies;
import com.patipp.common.security.CurrentUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookies refreshCookies;
    private final CurrentUser currentUser;
    private final GoogleAuthProperties google;

    public AuthController(AuthService authService, RefreshCookies refreshCookies, CurrentUser currentUser,
                          GoogleAuthProperties google) {
        this.authService = authService;
        this.refreshCookies = refreshCookies;
        this.currentUser = currentUser;
        this.google = google;
    }

    /** The OAuth client id is public. Never expose any server credentials here. */
    @GetMapping("/providers")
    public ProviderConfiguration providers() {
        return new ProviderConfiguration(google.isConfigured(), google.isConfigured() ? google.clientId() : null);
    }

    public record ProviderConfiguration(boolean googleEnabled, String googleClientId) {}

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest httpRequest) {
        AuthService.SessionGrant grant = authService.register(request, userAgent(httpRequest));
        return respondWithSession(grant, HttpStatus.CREATED);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest httpRequest) {
        AuthService.SessionGrant grant = authService.login(request, userAgent(httpRequest));
        return respondWithSession(grant, HttpStatus.OK);
    }

    /**
     * Exchanges a verified Google ID token for a PATIPP session.
     *
     * <p>Always 200, never 201, even when this call created the account. Which of the two
     * happened depends on whether the address was already registered, and a status code
     * that reported it would let anyone with a Google account discover who else has one
     * here.
     */
    @PostMapping("/oauth/google")
    public ResponseEntity<AuthResponse> signInWithGoogle(
            @Valid @RequestBody GoogleSignInRequest request,
            HttpServletRequest httpRequest) {
        AuthService.SessionGrant grant =
                authService.signInWithGoogle(request, userAgent(httpRequest));
        return respondWithSession(grant, HttpStatus.OK);
    }

    /** Exchanges the refresh cookie for a new access token and a rotated cookie. */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest httpRequest) {
        String rawRefreshToken = refreshCookies.readFrom(httpRequest);
        AuthService.SessionGrant grant = authService.refresh(rawRefreshToken, userAgent(httpRequest));
        return respondWithSession(grant, HttpStatus.OK);
    }

    /**
     * Revokes the current session. Always returns 204, even when no cookie was sent -
     * logging out is idempotent, and a client that has already lost its cookie should not
     * be handed an error for trying to be tidy.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest httpRequest) {
        authService.logout(refreshCookies.readFrom(httpRequest));
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    /** Revokes every session for the caller, on every device. */
    @PostMapping("/logout-everywhere")
    public ResponseEntity<Void> logoutEverywhere() {
        authService.logoutEverywhere(currentUser.requireId());
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    private ResponseEntity<AuthResponse> respondWithSession(AuthService.SessionGrant grant,
                                                            HttpStatus status) {
        String cookie = refreshCookies.issue(grant.rawRefreshToken(), grant.refreshTtl()).toString();
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, cookie)
                .body(grant.response());
    }

    private String userAgent(HttpServletRequest request) {
        return request.getHeader(HttpHeaders.USER_AGENT);
    }
}
