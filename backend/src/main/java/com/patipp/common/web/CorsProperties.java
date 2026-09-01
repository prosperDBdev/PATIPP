package com.patipp.common.web;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param allowedOrigins exact origins permitted to call the API. Wildcards are
 *                       intentionally unsupported: the API sends credentials, and
 *                       the CORS specification forbids "*" with credentials.
 */
@ConfigurationProperties(prefix = "patipp.cors")
public record CorsProperties(List<String> allowedOrigins) {

    public CorsProperties {
        allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
    }
}
