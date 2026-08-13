package co.pitayagroup.mcp.broadworks.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-origin (CORS) configuration for the MCP transport and the OAuth / discovery endpoints.
 *
 * <p>Browser-hosted MCP clients call {@code /mcp}, the RFC 9728 / RFC 8414 metadata documents,
 * {@code /oauth/register} and {@code /oauth2/token} with JavaScript, so each is preceded by a
 * {@code OPTIONS} preflight and needs {@code Access-Control-Allow-Origin} on the response. Without
 * it the browser aborts the request before the user ever sees the Google login page, and the server
 * side looks like nothing happened. The {@code WWW-Authenticate} challenge is also exposed, since it
 * carries the {@code resource_metadata} URL a client needs to start the OAuth flow.</p>
 *
 * <p>Only the configured origins are echoed — this doubles as the MCP specification's recommended
 * {@code Origin} check against DNS-rebinding — and credentials (cookies) are never allowed: MCP
 * clients authenticate with a bearer token.</p>
 *
 * @param allowedOrigins origins allowed to call the MCP/OAuth endpoints cross-origin (env
 *                       {@code CORS_ALLOWED_ORIGINS}); defaults to
 *                       {@link #WELL_KNOWN_CLIENT_ORIGINS} when unset.
 * @param enabled        whether CORS handling is installed at all (default {@code true}).
 */
@ConfigurationProperties(prefix = "broadworks.cors")
public record CorsProperties(
        List<String> allowedOrigins,
        Boolean enabled
) {

    /** Origins of the well-known browser-hosted MCP clients. */
    public static final List<String> WELL_KNOWN_CLIENT_ORIGINS = List.of(
            "https://claude.ai",
            "https://claude.com",
            "https://chatgpt.com",
            "https://grok.com"
    );

    public CorsProperties {
        allowedOrigins = allowedOrigins == null
                ? List.of()
                : allowedOrigins.stream().filter(o -> o != null && !o.isBlank()).map(String::trim).toList();
        enabled = enabled == null ? Boolean.TRUE : enabled;
    }

    /** @return the configured origins, or the well-known client origins when none were configured. */
    public List<String> effectiveAllowedOrigins() {
        return allowedOrigins.isEmpty() ? WELL_KNOWN_CLIENT_ORIGINS : allowedOrigins;
    }

    /** @return {@code true} when CORS handling should be installed on the security filter chains. */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
