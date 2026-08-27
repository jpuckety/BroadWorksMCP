package co.pitayagroup.mcp.broadworks.auth.oauth;

import java.util.function.Consumer;

import co.pitayagroup.mcp.broadworks.config.RedirectAllowlistProperties;

import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Redirect-URI validator that applies RFC 8252 native-app matching to loopback HTTP URIs.
 *
 * <p>Spring Authorization Server already allows any port for loopback <em>IP</em> literals
 * ({@code 127.0.0.1}, {@code ::1}) but uses exact string matching for {@code localhost}, and it
 * never treats {@code localhost} and {@code 127.0.0.1} as the same host. Desktop MCP clients
 * (Inspector, Claude Desktop, Cursor, VS Code) mix those hostnames and bind a new ephemeral port
 * at login, which SAS rejects with {@code invalid_request} / {@code redirect_uri}.</p>
 *
 * <p>This validator accepts a loopback request when scheme, path and query match a registered
 * loopback URI, ignoring port and equating loopback hosts ({@code localhost}, {@code 127.0.0.1},
 * {@code ::1}, other 127/8 addresses). Non-loopback URIs, fragments, and missing
 * {@code redirect_uri} are delegated to
 * {@link OAuth2AuthorizationCodeRequestAuthenticationValidator#DEFAULT_REDIRECT_URI_VALIDATOR}.</p>
 */
public final class LoopbackAwareRedirectUriValidator
        implements Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> {

    public static final LoopbackAwareRedirectUriValidator INSTANCE = new LoopbackAwareRedirectUriValidator();

    private LoopbackAwareRedirectUriValidator() {
    }

    @Override
    public void accept(OAuth2AuthorizationCodeRequestAuthenticationContext context) {
        final OAuth2AuthorizationCodeRequestAuthenticationToken authentication =
                context.getAuthentication();
        final String requestedRedirectUri = authentication.getRedirectUri();
        if (StringUtils.hasText(requestedRedirectUri)
                && matchesRegisteredLoopbackIgnoringPort(requestedRedirectUri, context.getRegisteredClient())) {
            return;
        }
        OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_REDIRECT_URI_VALIDATOR.accept(context);
    }

    /**
     * @return {@code true} when {@code requestedRedirectUri} is a loopback URI whose scheme, path
     * and query match a registered loopback URI, ignoring port and equating loopback hosts.
     */
    static boolean matchesRegisteredLoopbackIgnoringPort(String requestedRedirectUri, RegisteredClient client) {
        return matchesRegisteredLoopbackIgnoringPort(requestedRedirectUri, client.getRedirectUris());
    }

    static boolean matchesRegisteredLoopbackIgnoringPort(String requestedRedirectUri,
                                                         Iterable<String> registeredRedirectUris) {
        final UriComponents requested = parse(requestedRedirectUri);
        if (requested == null
                || requested.getFragment() != null
                || !RedirectAllowlistProperties.isLoopbackHost(requested.getHost())) {
            return false;
        }
        for (String registeredRedirectUri : registeredRedirectUris) {
            if (loopbackEqualsIgnoringPort(registeredRedirectUri, requested)) {
                return true;
            }
        }
        return false;
    }

    private static boolean loopbackEqualsIgnoringPort(String registeredRedirectUri, UriComponents requested) {
        final UriComponents registered = parse(registeredRedirectUri);
        if (registered == null
                || registered.getFragment() != null
                || registered.getUserInfo() != null
                || requested.getUserInfo() != null
                || !RedirectAllowlistProperties.isLoopbackHost(registered.getHost())) {
            return false;
        }
        return schemeEquals(registered.getScheme(), requested.getScheme())
                && pathEquals(registered.getPath(), requested.getPath())
                && queryEquals(registered.getQuery(), requested.getQuery());
    }

    private static boolean schemeEquals(String left, String right) {
        return left != null && left.equalsIgnoreCase(right);
    }

    private static boolean pathEquals(String left, String right) {
        return normalizePath(left).equals(normalizePath(right));
    }

    private static String normalizePath(String path) {
        return path == null || path.isEmpty() ? "/" : path;
    }

    private static boolean queryEquals(String left, String right) {
        if (left == null || left.isEmpty()) {
            return right == null || right.isEmpty();
        }
        return left.equals(right);
    }

    private static UriComponents parse(String uri) {
        final UriComponentsBuilder builder = parseBuilder(uri);
        return builder == null ? null : builder.build();
    }

    private static UriComponentsBuilder parseBuilder(String uri) {
        try {
            return UriComponentsBuilder.fromUriString(uri);
        } catch (Exception ex) {
            return null;
        }
    }
}
