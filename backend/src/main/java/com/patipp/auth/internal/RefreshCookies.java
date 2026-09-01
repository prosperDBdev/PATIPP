package com.patipp.auth.internal;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * Builds and reads the refresh cookie.
 *
 * <p>Three properties do the security work here. {@code HttpOnly} keeps the token out of
 * reach of any script, so an XSS bug cannot steal the long-lived credential.
 * {@code SameSite=Strict} means the browser never attaches it to a cross-site request,
 * which is what makes it safe to run this API without CSRF tokens. And the narrow
 * {@code Path} means the cookie is only ever sent to the two endpoints that need it, so it
 * is absent from every ordinary API call.
 */
@Component
public class RefreshCookies {

    public static final String COOKIE_NAME = "patipp_refresh";
    private static final String COOKIE_PATH = "/api/v1/auth";

    private final JwtProperties properties;

    public RefreshCookies(JwtProperties properties) {
        this.properties = properties;
    }

    public ResponseCookie issue(String rawToken, Duration ttl) {
        return baseCookie(rawToken)
                .maxAge(ttl)
                .build();
    }

    /** An empty, immediately-expiring cookie that clears the browser's copy on logout. */
    public ResponseCookie clear() {
        return baseCookie("")
                .maxAge(Duration.ZERO)
                .build();
    }

    private ResponseCookie.ResponseCookieBuilder baseCookie(String value) {
        return ResponseCookie.from(COOKIE_NAME, value)
                .httpOnly(true)
                // Secure is disabled only for plain-HTTP local development; production
                // configuration must set patipp.jwt.secure-cookie=true.
                .secure(properties.secureCookie())
                .sameSite("Strict")
                .path(COOKIE_PATH);
    }

    public String readFrom(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (COOKIE_NAME.equals(cookie.getName())) {
                String value = cookie.getValue();
                return (value == null || value.isBlank()) ? null : value;
            }
        }
        return null;
    }
}
