package co.pitayagroup.mcp.broadworks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The externally reachable public hostname of this server, from which the base URL is built.
 *
 * <p>The base URL is {@code https://<hostname>}. It is used to build the Google login callback URI
 * ({@code <baseUrl>/login/oauth2/code/google}), the issuer identifier in discovery documents, the
 * MCP resource audience ({@code <baseUrl>/mcp}), and the {@code resource_metadata} URL in the
 * {@code WWW-Authenticate} challenge.</p>
 *
 * <p>When no hostname is configured (local development) the base URL defaults to
 * {@link #DEFAULT_BASE_URL}.</p>
 *
 * @param hostname the public DNS hostname without scheme or path, e.g. {@code mcp.example.com}.
 */
@ConfigurationProperties(prefix = "broadworks.public")
public record PublicBaseUrlProperties(
        String hostname
) {
    /** Default base URL for local HTTP development (used when no hostname is configured). */
    public static final String DEFAULT_BASE_URL = "http://localhost:8080";

    public PublicBaseUrlProperties {
        hostname = normalizeHostname(hostname);
    }

    /**
     * Reduce the configured value to a bare {@code host[:port]}: tolerate a full URL or leading
     * scheme by stripping it, and drop any path/trailing slash.
     */
    private static String normalizeHostname(String value) {
        if (value == null) {
            return "";
        }
        String host = value.trim();
        final int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }
        final int slash = host.indexOf('/');
        if (slash >= 0) {
            host = host.substring(0, slash);
        }
        return host;
    }

    /**
     * The externally reachable base URL, without a trailing slash. Built as
     * {@code https://<hostname>}, or {@link #DEFAULT_BASE_URL} when no hostname is configured.
     */
    public String baseUrl() {
        if (hostname == null || hostname.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return "https://" + hostname;
    }

    /**
     * Spring Security OAuth2 login callback for the Google registration
     * ({@code <baseUrl>/login/oauth2/code/google}).
     */
    public String callbackUri() {
        return baseUrl() + "/login/oauth2/code/google";
    }

    /** Canonical MCP protected-resource URL ({@code <baseUrl>/mcp}) used as token audience. */
    public String mcpResourceUrl() {
        return baseUrl() + "/mcp";
    }

    /**
     * Case-insensitive resource match after stripping a single trailing slash from each side.
     * Used for RFC 8707 resource-indicator comparison.
     */
    public static boolean resourceMatches(String requested, String canonical) {
        if (requested == null || canonical == null) {
            return false;
        }
        return stripTrailingSlash(requested).equalsIgnoreCase(stripTrailingSlash(canonical));
    }

    private static String stripTrailingSlash(String value) {
        if (value.endsWith("/") && value.length() > 1) {
            return value.substring(0, value.length() - 1);
        }
        return value;
    }
}
