package com.broadworks.mcp.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Allow-list controlling which client redirect URIs may be registered / used.
 *
 * <p>HTTPS redirect URIs must appear in {@link #allowedHttpsPrefixes()}. Loopback HTTP
 * ({@code http://127.0.0.1}, {@code http://localhost}) and non-HTTP custom schemes (desktop clients)
 * are always allowed, per OAuth 2.1 native-app guidance.</p>
 *
 * @param allowedHttpsPrefixes list of allowed HTTPS redirect-URI prefixes.
 */
@ConfigurationProperties(prefix = "broadworks.auth.redirect")
public record RedirectAllowlistProperties(
        List<String> allowedHttpsPrefixes
) {
    public RedirectAllowlistProperties {
        allowedHttpsPrefixes = allowedHttpsPrefixes == null ? List.of() : List.copyOf(allowedHttpsPrefixes);
    }

    /**
     * @return {@code true} if the supplied redirect URI is permitted: any loopback HTTP address, any
     * non-HTTP custom scheme, or an HTTPS URI matching a configured prefix.
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
        if ("https".equalsIgnoreCase(scheme)) {
            return allowedHttpsPrefixes.stream().anyMatch(redirectUri::startsWith);
        }
        if ("http".equalsIgnoreCase(scheme)) {
            final String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "[::1]".equals(host)
                    || "::1".equals(host);
        }
        // Custom scheme (e.g. desktop app deep link) is allowed.
        return true;
    }
}
