package co.pitayagroup.mcp.broadworks.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * Allow-list controlling which client redirect URIs may be registered / used.
 *
 * <p>Validation mirrors the MCP-client rules used by desktop and hosted clients:</p>
 * <ul>
 *   <li>Loopback HTTP ({@code 127.0.0.0/8}, {@code localhost}, {@code ::1}) is always allowed
 *       (RFC 8252 native apps).</li>
 *   <li>HTTPS is allowed when the host is non-empty and either the effective allow-list is empty
 *       (enforcement off) or the URI structurally matches an allow-list entry.</li>
 *   <li>Custom schemes (e.g. {@code cursor://}) are always allowed, except {@code javascript},
 *       {@code data}, {@code vbscript}, {@code file}, and schemes containing {@code '.'}.</li>
 * </ul>
 *
 * <p>HTTPS matching is structural, not textual: entry and candidate are parsed as {@link URI} and
 * must share scheme, host and port exactly, with the candidate path equal to the entry path or
 * below it on a path-segment boundary. So {@code https://app.example.com/cb} permits
 * {@code https://app.example.com/cb/done} but neither {@code https://app.example.com/cbx} nor
 * {@code https://app.example.com.attacker.tld/cb}. A scheme-only web entry ({@code https://}) never
 * allow-lists every HTTPS host.</p>
 *
 * <p>The callbacks of the well-known hosted MCP clients ({@link #WELL_KNOWN_CLIENT_REDIRECTS}) are
 * additionally permitted by default so those clients can register out of the box; set
 * {@code broadworks.auth.redirect.allow-well-known-clients=false} (env
 * {@code OAUTH_ALLOW_WELL_KNOWN_CLIENTS}) to omit them. With well-known clients disabled and an
 * empty prefix list, HTTPS enforcement is off (any HTTPS host).</p>
 *
 * @param allowedHttpsPrefixes    list of allowed HTTPS redirect-URI prefixes (env
 *                                {@code OAUTH_REDIRECT_ALLOWLIST}).
 * @param allowWellKnownClients   whether {@link #WELL_KNOWN_CLIENT_REDIRECTS} are allowed in addition
 *                                to {@code allowedHttpsPrefixes} (default {@code true}).
 */
@ConfigurationProperties(prefix = "broadworks.auth.redirect")
public record RedirectAllowlistProperties(
        List<String> allowedHttpsPrefixes,
        Boolean allowWellKnownClients
) {

    /**
     * Redirect URIs of the widely used MCP clients. Each entry is a normal allow-list prefix, so the
     * usual structural match applies (exact scheme/host/port, path at or below the entry path).
     */
    public static final List<String> WELL_KNOWN_CLIENT_REDIRECTS = List.of(
            // Anthropic Claude (web / desktop connector callbacks).
            "https://claude.ai/api/mcp/auth_callback",
            "https://claude.com/api/mcp/auth_callback",
            // OpenAI ChatGPT connectors.
            "https://chatgpt.com/connector_platform_oauth_redirect",
            // xAI Grok connectors.
            "https://grok.com/connectors-oauth-exchange-code",
            // VS Code (and the Insiders build) relay the callback through vscode.dev.
            "https://vscode.dev/redirect",
            "https://insiders.vscode.dev/redirect"
    );

    // Two constructors, so the canonical one must be marked as the binding target explicitly.
    @ConstructorBinding
    public RedirectAllowlistProperties {
        allowedHttpsPrefixes = allowedHttpsPrefixes == null
                ? List.of()
                : allowedHttpsPrefixes.stream().filter(p -> p != null && !p.isBlank()).toList();
        allowWellKnownClients = allowWellKnownClients == null ? Boolean.TRUE : allowWellKnownClients;
    }

    /** Convenience constructor keeping the well-known client callbacks enabled (the default). */
    public RedirectAllowlistProperties(List<String> allowedHttpsPrefixes) {
        this(allowedHttpsPrefixes, Boolean.TRUE);
    }

    /**
     * @return {@code true} if the supplied redirect URI is permitted: loopback HTTP, a custom scheme
     * used by desktop MCP clients, or HTTPS matching the effective allow-list (or any HTTPS host when
     * that list is empty).
     */
    public boolean isAllowed(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            return false;
        }
        final URI uri = parse(redirectUri);
        if (uri == null) {
            return false;
        }
        final String rawScheme = uri.getScheme();
        if (rawScheme == null || rawScheme.isBlank()) {
            return false;
        }
        // Credentials in a redirect URI are never legitimate and confuse authority comparison.
        if (uri.getUserInfo() != null) {
            return false;
        }
        final String scheme = rawScheme.toLowerCase(Locale.ROOT);
        // MCP clients commonly use loopback HTTP or custom schemes (e.g. cursor://).
        if ("http".equals(scheme)) {
            return isLoopbackHost(uri.getHost());
        }
        if ("https".equals(scheme)) {
            final String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            final List<String> allowlist = effectiveAllowlist();
            // Blank allowlist → enforcement off (any HTTPS host).
            if (allowlist.isEmpty()) {
                return true;
            }
            return allowlist.stream().anyMatch(entry -> matches(entry, uri));
        }
        // Custom URI schemes (desktop / native clients).
        if (!"javascript".equals(scheme)
                && !"data".equals(scheme)
                && !"vbscript".equals(scheme)
                && !"file".equals(scheme)) {
            return !scheme.contains(".");
        }
        return false;
    }

    /** The configured prefixes plus, unless disabled, the well-known hosted MCP client callbacks. */
    private List<String> effectiveAllowlist() {
        if (!Boolean.TRUE.equals(allowWellKnownClients)) {
            return allowedHttpsPrefixes;
        }
        return Stream.concat(allowedHttpsPrefixes.stream(), WELL_KNOWN_CLIENT_REDIRECTS.stream()).toList();
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
     * Loopback HTTP hosts accepted for native-app redirects. Literal IPs are parsed without DNS so a
     * hostname cannot become loopback by resolution. {@code localhost} is included so authorize-time
     * matching can apply RFC 8252 ephemeral-port rules to the hostname MCP clients actually send.
     */
    public static boolean isLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        String hostname = host.trim();
        if (hostname.startsWith("[") && hostname.endsWith("]") && hostname.length() > 2) {
            hostname = hostname.substring(1, hostname.length() - 1);
        }
        if ("localhost".equalsIgnoreCase(hostname)) {
            return true;
        }
        if (!looksLikeIpLiteral(hostname)) {
            return false;
        }
        try {
            return InetAddress.getByName(hostname).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }

    private static boolean looksLikeIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        int dots = 0;
        for (int i = 0; i < host.length(); i++) {
            final char c = host.charAt(i);
            if (c == '.') {
                dots++;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return dots == 3;
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
