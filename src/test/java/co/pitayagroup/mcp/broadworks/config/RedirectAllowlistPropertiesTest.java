package co.pitayagroup.mcp.broadworks.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RedirectAllowlistPropertiesTest {

    @Test
    void loopbackHttpIsAlwaysAllowed() {
        final RedirectAllowlistProperties props = new RedirectAllowlistProperties(List.of());
        assertThat(props.isAllowed("http://127.0.0.1:8123/callback")).isTrue();
        assertThat(props.isAllowed("http://127.0.0.2/cb")).isTrue();
        assertThat(props.isAllowed("http://localhost/cb")).isTrue();
        assertThat(props.isAllowed("http://[::1]/1/cb")).isTrue();
        assertThat(props.isAllowed("http://example.com/cb")).isFalse();
        assertThat(props.isAllowed("http://192.168.1.1/cb")).isFalse();
    }

    @Test
    void customSchemesAreAlwaysAllowed() {
        final RedirectAllowlistProperties empty = new RedirectAllowlistProperties(List.of());
        assertThat(empty.isAllowed("cursor://auth/callback")).isTrue();
        assertThat(empty.isAllowed("vscode://other")).isTrue();
        assertThat(empty.isAllowed("myapp:callback")).isTrue();
    }

    @Test
    void dangerousAndDottedSchemesAreRejected() {
        final RedirectAllowlistProperties props = new RedirectAllowlistProperties(List.of());
        assertThat(props.isAllowed("javascript:alert(1)")).isFalse();
        assertThat(props.isAllowed("data:text/html,hello")).isFalse();
        assertThat(props.isAllowed("vbscript:msgbox(1)")).isFalse();
        assertThat(props.isAllowed("file:///etc/passwd")).isFalse();
        assertThat(props.isAllowed("com.example.app://callback")).isFalse();
    }

    @Test
    void httpsRequiresAllowlistPrefix() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://grok.x.ai/"));
        assertThat(props.isAllowed("https://grok.x.ai/oauth/callback")).isTrue();
        assertThat(props.isAllowed("https://evil.example.com/cb")).isFalse();
    }

    @Test
    void emptyAllowlistWithWellKnownDisabledAllowsAnyHttpsHost() {
        final RedirectAllowlistProperties props = new RedirectAllowlistProperties(List.of(), false);
        assertThat(props.isAllowed("https://evil.example.com/cb")).isTrue();
        assertThat(props.isAllowed("https://app.example.com/oauth/callback")).isTrue();
        assertThat(props.isAllowed("https:///nohost")).isFalse();
    }

    @Test
    void hostMustMatchExactlyRatherThanByPrefix() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://app.example.com"));
        assertThat(props.isAllowed("https://app.example.com/cb")).isTrue();
        assertThat(props.isAllowed("https://APP.example.com/cb")).isTrue();
        assertThat(props.isAllowed("https://app.example.com.attacker.tld/cb")).isFalse();
        assertThat(props.isAllowed("https://app.example.com@attacker.tld/cb")).isFalse();
        assertThat(props.isAllowed("https://attacker.tld/https://app.example.com")).isFalse();
    }

    @Test
    void pathMustMatchOnASegmentBoundary() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://app.example.com/cb"));
        assertThat(props.isAllowed("https://app.example.com/cb")).isTrue();
        assertThat(props.isAllowed("https://app.example.com/cb/x")).isTrue();
        assertThat(props.isAllowed("https://app.example.com/cbx")).isFalse();
        assertThat(props.isAllowed("https://app.example.com/other")).isFalse();
        assertThat(props.isAllowed("https://app.example.com/cb/../other")).isFalse();
    }

    @Test
    void schemeAndPortMustMatch() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://app.example.com:8443/cb"));
        assertThat(props.isAllowed("https://app.example.com:8443/cb")).isTrue();
        assertThat(props.isAllowed("https://app.example.com/cb")).isFalse();

        final RedirectAllowlistProperties defaultPort =
                new RedirectAllowlistProperties(List.of("https://app.example.com/cb"));
        assertThat(defaultPort.isAllowed("https://app.example.com:443/cb")).isTrue();
        assertThat(defaultPort.isAllowed("https://app.example.com:8443/cb")).isFalse();
        // A non-loopback plain-HTTP candidate is never allowed, even for an allow-listed host.
        assertThat(defaultPort.isAllowed("http://app.example.com/cb")).isFalse();
    }

    @Test
    void wellKnownClientCallbacksAreAllowedByDefault() {
        final RedirectAllowlistProperties props = new RedirectAllowlistProperties(List.of());
        assertThat(props.isAllowed("https://claude.ai/api/mcp/auth_callback")).isTrue();
        assertThat(props.isAllowed("https://chatgpt.com/connector_platform_oauth_redirect")).isTrue();
        assertThat(props.isAllowed("https://grok.com/connectors-oauth-exchange-code")).isTrue();
        assertThat(props.isAllowed("https://vscode.dev/redirect")).isTrue();
        // Still a structural match: neither a different path on the same host nor a look-alike host.
        assertThat(props.isAllowed("https://claude.ai/other")).isFalse();
        assertThat(props.isAllowed("https://claude.ai.attacker.tld/api/mcp/auth_callback")).isFalse();
    }

    @Test
    void wellKnownClientCallbacksCanBeDisabled() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://app.example.com/cb"), false);
        assertThat(props.isAllowed("https://claude.ai/api/mcp/auth_callback")).isFalse();
        assertThat(props.isAllowed("https://app.example.com/cb")).isTrue();
        // Loopback stays allowed: it is required by RFC 8252, not by the allow-list.
        assertThat(props.isAllowed("http://127.0.0.1:8123/callback")).isTrue();
    }

    @Test
    void wildcardWebSchemeEntriesAndMalformedUrisAreRejected() {
        final RedirectAllowlistProperties wildcard =
                new RedirectAllowlistProperties(List.of("https://", "https:"));
        assertThat(wildcard.isAllowed("https://evil.example.com/cb")).isFalse();

        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://app.example.com/cb"));
        assertThat(props.isAllowed("https://app.example.com/c b")).isFalse();
        assertThat(props.isAllowed("not a uri")).isFalse();
        assertThat(props.isAllowed("/relative/cb")).isFalse();
        assertThat(props.isAllowed(null)).isFalse();
    }
}
