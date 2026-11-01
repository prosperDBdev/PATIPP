package com.patipp.auth.internal;

import com.patipp.auth.api.AuthDtos.AuthResponse;
import com.patipp.auth.api.AuthDtos.GoogleSignInRequest;
import com.patipp.auth.api.AuthDtos.LoginRequest;
import com.patipp.auth.api.AuthDtos.RegisterRequest;
import com.patipp.auth.api.AuthDtos.UserSummary;
import com.patipp.auth.domain.AuthProvider;
import com.patipp.auth.domain.RefreshToken;
import com.patipp.auth.domain.RefreshTokenRepository;
import com.patipp.auth.domain.User;
import com.patipp.auth.domain.UserRepository;
import com.patipp.common.error.ConflictException;
import com.patipp.common.error.UnauthorizedException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Registration, login, refresh rotation and logout.
 *
 * <p>Sessions are split deliberately. A short-lived signed access token is held in browser
 * memory and never persisted, so XSS cannot lift it from storage. A long-lived opaque
 * refresh token lives in an httpOnly cookie and is stored hashed, so it can be revoked and
 * is unreadable by script. Neither half is useful for long on its own.
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final RefreshTokenFactory refreshTokenFactory;
    private final JwtProperties jwtProperties;
    private final GoogleIdTokenVerifier googleVerifier;
    private final ProviderAccountResolver providerAccounts;
    private final Clock clock;

    public AuthService(UserRepository users,
                       RefreshTokenRepository refreshTokens,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       RefreshTokenFactory refreshTokenFactory,
                       JwtProperties jwtProperties,
                       GoogleIdTokenVerifier googleVerifier,
                       ProviderAccountResolver providerAccounts,
                       Clock clock) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTokenFactory = refreshTokenFactory;
        this.jwtProperties = jwtProperties;
        this.googleVerifier = googleVerifier;
        this.providerAccounts = providerAccounts;
        this.clock = clock;
    }

    @Transactional
    public SessionGrant register(RegisterRequest request, String userAgent) {
        String email = User.normaliseEmail(request.email());
        if (users.existsByEmail(email)) {
            // Registration is not an anonymous endpoint in practice - you are telling the
            // system who you are - so reporting the collision is acceptable and far kinder
            // than a generic failure. Login remains deliberately vague.
            throw new ConflictException("auth.email_taken", "That email address is already registered.");
        }

        User user = User.register(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName(),
                request.timezone());
        users.save(user);

        log.info("Registered user {}", user.id());
        return grantSession(user, userAgent);
    }

    @Transactional
    public SessionGrant login(LoginRequest request, String userAgent) {
        String email = User.normaliseEmail(request.email());
        Optional<User> found = users.findByEmail(email);

        if (found.isEmpty()) {
            // Hash anyway so a missing account and a wrong password take comparable time.
            // Without this, response timing alone enumerates registered addresses.
            passwordEncoder.encode(request.password());
            throw invalidCredentials();
        }

        User user = found.get();
        if (!user.hasPassword()) {
            // A provider-only account, reachable since V10 made password_hash nullable.
            // Hash anyway and report the usual message: saying "this account uses Google"
            // would confirm the address is registered, and login is the one endpoint an
            // attacker can point at addresses that are not theirs. The login screen tells
            // everyone to try the Google button on failure, which rescues the confused
            // learner without answering the question for anybody else.
            passwordEncoder.encode(request.password());
            throw invalidCredentials();
        }
        if (!passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw invalidCredentials();
        }

        user.touchLastActive(now());
        return grantSession(user, userAgent);
    }

    /**
     * Signs in with a Google ID token, registering the learner if this is their first visit.
     *
     * <p>Google is an identity provider here and nothing more. It answers "who is this, and
     * has Google verified the address?", and from that point the session is PATIPP's own:
     * the same access token, the same rotating refresh cookie with reuse detection, the
     * same {@code users} row every other table already points at. Google is never consulted
     * again, so a learner signed in this way is unaffected if it becomes unreachable.
     *
     * <p>There is no separate "register with Google" endpoint because the distinction is not
     * one the caller can make: whether this is a new account depends on state only the
     * server can see. Both outcomes return 200 for the same reason - telling them apart
     * would reveal whether an address is already registered.
     */
    @Transactional
    public SessionGrant signInWithGoogle(GoogleSignInRequest request, String userAgent) {
        ExternalIdentity identity = googleVerifier.verify(request.idToken());
        Instant now = now();

        User user = providerAccounts.resolve(
                AuthProvider.GOOGLE, identity, request.timezone(), now);
        user.touchLastActive(now);

        return grantSession(user, userAgent);
    }

    /**
     * Rotates a refresh token: the presented token is revoked and a fresh one issued.
     *
     * <p>If a token that has already been rotated is presented, it means a copy escaped -
     * either the legitimate holder replayed a stale cookie or an attacker stole one. Since
     * the two are indistinguishable, every session for that user is revoked. Forcing a
     * re-login is a small cost; leaving a live thief is not.
     */
    @Transactional
    public SessionGrant refresh(String rawRefreshToken, String userAgent) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            throw new UnauthorizedException("auth.refresh_missing", "No refresh token was supplied.");
        }

        String hash = refreshTokenFactory.hash(rawRefreshToken);
        RefreshToken stored = refreshTokens.findByTokenHash(hash)
                .orElseThrow(() -> new UnauthorizedException(
                        "auth.refresh_invalid", "That session is no longer valid. Please sign in again."));

        Instant now = now();

        if (stored.isAlreadyRotated()) {
            log.warn("Refresh token reuse detected for user {} - revoking all sessions", stored.userId());
            refreshTokens.revokeAllForUser(stored.userId(), now);
            throw new UnauthorizedException(
                    "auth.refresh_reused", "That session is no longer valid. Please sign in again.");
        }

        if (!stored.isActive(now)) {
            throw new UnauthorizedException(
                    "auth.refresh_expired", "That session has expired. Please sign in again.");
        }

        User user = users.findById(stored.userId())
                .orElseThrow(() -> new UnauthorizedException(
                        "auth.refresh_invalid", "That session is no longer valid. Please sign in again."));

        SessionGrant grant = grantSession(user, userAgent);
        stored.replaceWith(grant.refreshTokenId(), now);
        user.touchLastActive(now);
        return grant;
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return;
        }
        refreshTokens.findByTokenHash(refreshTokenFactory.hash(rawRefreshToken))
                .ifPresent(token -> token.revoke(now()));
    }

    @Transactional
    public void logoutEverywhere(java.util.UUID userId) {
        int revoked = refreshTokens.revokeAllForUser(userId, now());
        log.info("Revoked {} sessions for user {}", revoked, userId);
    }

    private SessionGrant grantSession(User user, String userAgent) {
        Instant now = now();

        JwtService.IssuedAccessToken access = jwtService.issueAccessToken(user.id(), user.email(), now);

        String rawRefresh = refreshTokenFactory.mintRawToken();
        RefreshToken refreshToken = RefreshToken.issue(
                user.id(),
                refreshTokenFactory.hash(rawRefresh),
                now.plus(Duration.ofDays(jwtProperties.refreshTtlDays())),
                userAgent);
        refreshTokens.save(refreshToken);

        AuthResponse body = new AuthResponse(
                access.token(),
                "Bearer",
                access.expiresInSeconds(),
                new UserSummary(user.id(), user.email(), user.displayName(), user.timezone()));

        return new SessionGrant(body, rawRefresh, refreshToken.id(),
                Duration.ofDays(jwtProperties.refreshTtlDays()));
    }

    private UnauthorizedException invalidCredentials() {
        // One message for both "no such account" and "wrong password", so the response
        // cannot be used to discover which addresses are registered.
        return new UnauthorizedException("auth.invalid_credentials", "Email or password is incorrect.");
    }

    private Instant now() {
        return clock.instant();
    }

    /**
     * @param rawRefreshToken the only moment the raw token exists outside the client; the
     *                        controller writes it straight into a cookie and drops it.
     */
    public record SessionGrant(
            AuthResponse response,
            String rawRefreshToken,
            java.util.UUID refreshTokenId,
            Duration refreshTtl) {
    }
}
