package com.broadworks.mcp.config;

import java.net.URI;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Allow-list controlling which client redirect URIs may be registered / used.
 *
 * <p>HTTPS redirect URIs and non-HTTP custom schemes (desktop clients) must match an entry in
 * {@link #allowedHttpsPrefixes()} (env {@code OAUTH_REDIRECT_ALLOWLIST}). Matching is structural, not
 * textual: entry and candidate are parsed as {@link URI} and must share scheme, host and port exactly,
 * with the candidate path equal to the entry path or below it on a path-segment boundary. So
 * {@code https://app.example.com/cb} permits {@code https://app.example.com/cb/done} but neither
 * {@code https://app.example.com/cbx} nor {@code https://app.example.com.attacker.tld/cb}. A
 * scheme-only entry ({@code cursor://}) registers a whole custom scheme, which is how native clients
 * are allow-listed. Loopback HTTP ({@code http://127.0.0.1}, {@code http://localhost},
 * {@code [::1]}) is always allowed per OAuth 2.1 native-app guidance (RFC 8252).</p>
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
        final URI uri = parse(redirectUri);
        if (uri == null) {
            return false;
        }
        final String scheme = uri.getScheme();
        if (scheme == null) {
            return false;
        }
        // Credentials in a redirect URI are never legitimate and confuse authority comparison.
        if (uri.getUserInfo() != null) {
            return false;
        }
        if ("http".equalsIgnoreCase(scheme)) {
            final String host = uri.getHost();
            return "localhost".equalsIgnoreCase(host)
                    || "127.0.0.1".equals(host)
                    || "[::1]".equals(host)
                    || "::1".equals(host);
        }
        // HTTPS and custom schemes (e.g. cursor://) require an allow-list entry match.
        return allowedHttpsPrefixes.stream().anyMatch(entry -> matches(entry, uri));
    }

    /**
     * Structural match of one allow-list entry against a candidate: identical scheme, host and port,
     * and a path at or below the entry path on a segment boundary.
     */
    private static boolean matches(String allowedEntry, URI candidate) {
        final String entry = allowedEntry.trim();
        final String schemeOnly = schemeOnlyEntry(entry);
        if (schemeOnly != null) {
            // A custom scheme is itself the native client's registered identifier, so there is no
            // authority to compare; http(s) may never be allow-listed wholesale.
            return !isWebScheme(schemeOnly) && schemeOnly.equalsIgnoreCase(candidate.getScheme());
        }
        final URI allowed = parse(entry);
        if (allowed == null || allowed.getScheme() == null || allowed.getHost() == null) {
            return false;
        }
        if (!allowed.getScheme().equalsIgnoreCase(candidate.getScheme())) {
            return false;
        }
        if (!allowed.getHost().equalsIgnoreCase(candidate.getHost())) {
            return false;
        }
        if (effectivePort(allowed) != effectivePort(candidate)) {
            return false;
        }
        return pathIsWithin(allowed.normalize().getPath(), candidate.normalize().getPath());
    }

    private static URI parse(String value) {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /**
     * @return the scheme of an entry that names only a scheme ({@code cursor://}, {@code cursor:}),
     * or {@code null} when the entry carries an authority.
     */
    private static String schemeOnlyEntry(String entry) {
        final int colon = entry.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        final String rest = entry.substring(colon + 1);
        if (!rest.isEmpty() && !"//".equals(rest)) {
            return null;
        }
        return entry.substring(0, colon);
    }

    private static boolean isWebScheme(String scheme) {
        return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
    }

    /** Explicit port, or the scheme default so {@code https://x} and {@code https://x:443} compare equal. */
    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())) {
            return 443;
        }
        return "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
    }

    /**
     * @return {@code true} if {@code candidatePath} equals the allow-listed path or extends it at a
     * path-segment boundary; an empty allow-listed path ({@code https://app.example.com/}) matches any
     * path on that authority.
     */
    private static boolean pathIsWithin(String allowedPath, String candidatePath) {
        final String base = stripTrailingSlashes(allowedPath == null ? "" : allowedPath);
        if (base.isEmpty()) {
            return true;
        }
        final String path = candidatePath == null ? "" : candidatePath;
        return path.equals(base) || path.startsWith(base + "/");
    }

    private static String stripTrailingSlashes(String path) {
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return path.substring(0, end);
    }
}
