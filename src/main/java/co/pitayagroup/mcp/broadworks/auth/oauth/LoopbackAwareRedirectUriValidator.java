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
 * Redirect-URI validator that applies RFC 8252 ephemeral-port matching to loopback HTTP URIs,
 * including {@code localhost}.
 *
 * <p>Spring Authorization Server already allows any port for loopback <em>IP</em> literals
 * ({@code 127.0.0.1}, {@code ::1}) but uses exact string matching for {@code localhost}. Desktop
 * MCP clients (Inspector, Claude Desktop, Cursor, VS Code) register a {@code localhost} callback
 * and then bind a new ephemeral port on the next login, which SAS rejects with
 * {@code invalid_request} / {@code redirect_uri}.</p>
 *
 * <p>Non-loopback URIs, fragments, and missing {@code redirect_uri} are delegated to
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
     * @return {@code true} when {@code requestedRedirectUri} is a loopback HTTP URI whose scheme,
     * host and path match a registered loopback URI, ignoring port as required by RFC 8252.
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
        final UriComponentsBuilder registeredBuilder = parseBuilder(registeredRedirectUri);
        if (registeredBuilder == null) {
            return false;
        }
        final UriComponents registered = registeredBuilder.build();
        if (!RedirectAllowlistProperties.isLoopbackHost(registered.getHost())) {
            return false;
        }
        registeredBuilder.port(requested.getPort());
        return registeredBuilder.build().toString().equals(requested.toString());
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
