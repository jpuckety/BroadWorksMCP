package com.broadworks.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The externally reachable base URL of this server.
 *
 * <p>Used to build the OAuth callback URI ({@code <baseUrl>/oauth/callback}), the issuer identifier
 * in discovery documents, and the {@code resource_metadata} URL in the {@code WWW-Authenticate}
 * challenge. Must be an HTTPS URL in production.</p>
 *
 * @param baseUrl fully-qualified base URL without a trailing slash, e.g.
 *                {@code https://mcp.example.com}.
 */
@ConfigurationProperties(prefix = "broadworks.public")
public record PublicBaseUrlProperties(
        String baseUrl
) {
    /** Default base URL for local HTTP development. */
    public static final String DEFAULT_BASE_URL = "http://localhost:8080";

    public PublicBaseUrlProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = DEFAULT_BASE_URL;
        }
        // Normalize: strip any trailing slash so callers can safely concatenate paths.
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
    }

    /** The full OAuth callback URI ({@code <baseUrl>/oauth/callback}). */
    public String callbackUri() {
        return baseUrl + "/oauth/callback";
    }
}
