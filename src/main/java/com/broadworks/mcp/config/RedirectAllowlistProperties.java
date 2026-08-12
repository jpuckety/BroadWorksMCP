package com.broadworks.mcp.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Allow-list controlling which client redirect URIs may be registered / used.
 *
 * <p>HTTPS redirect URIs and non-HTTP custom schemes (desktop clients) must match a prefix in
 * {@link #allowedHttpsPrefixes()} (env {@code OAUTH_REDIRECT_ALLOWLIST}). Loopback HTTP
 * ({@code http://127.0.0.1}, {@code http://localhost}, {@code [::1]}) is always allowed per OAuth
 * 2.1 native-app guidance.</p>
 *
 * @param allowedHttpsPrefixes list of allowed redirect-URI prefixes (HTTPS and custom schemes).
 */
@ConfigurationProperties(prefix = "broadworks.auth.redirect")
public record RedirectAllowlistProperties(
        List<String> allowedHttpsPrefixes
) {
    public RedirectAllowlistProperties {
        allowedHttpsPrefixes = allowedHttpsPrefixes == null
                ? List.of()
                : allowedHttpsPrefixes.stream().filter(p -> p != null && !p.isBlank()).toList();
    }

    /**
     * @return {@code true} if the supplied redirect URI is permitted: any loopback HTTP address, or
     * a URI (HTTPS or custom scheme) matching a configured prefix.
     */
    public boolean isAllowed(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return false;
        }
        final URI uri;
        try {
            uri = URI.create(redirectUri);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            final String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "[::1]".equals(host)
                    || "::1".equals(host);
        }
        // HTTPS and custom schemes (e.g. cursor://) require an allow-list prefix match.
        return allowedHttpsPrefixes.stream().anyMatch(redirectUri::startsWith);
    }
}
