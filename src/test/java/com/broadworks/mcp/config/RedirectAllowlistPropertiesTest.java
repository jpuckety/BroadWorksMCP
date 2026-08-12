package com.broadworks.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class RedirectAllowlistPropertiesTest {

    @Test
    void loopbackHttpIsAlwaysAllowed() {
        final RedirectAllowlistProperties props = new RedirectAllowlistProperties(List.of());
        assertThat(props.isAllowed("http://127.0.0.1:8123/callback")).isTrue();
        assertThat(props.isAllowed("http://localhost/cb")).isTrue();
        assertThat(props.isAllowed("http://[::1]/1/cb")).isTrue();
    }

    @Test
    void customSchemeRequiresAllowlistPrefix() {
        final RedirectAllowlistProperties empty = new RedirectAllowlistProperties(List.of());
        assertThat(empty.isAllowed("cursor://auth/callback")).isFalse();

        final RedirectAllowlistProperties allowlisted =
                new RedirectAllowlistProperties(List.of("cursor://", "https://app.example.com/"));
        assertThat(allowlisted.isAllowed("cursor://auth/callback")).isTrue();
        assertThat(allowlisted.isAllowed("vscode://other")).isFalse();
    }

    @Test
    void httpsRequiresAllowlistPrefix() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("https://grok.x.ai/"));
        assertThat(props.isAllowed("https://grok.x.ai/oauth/callback")).isTrue();
        assertThat(props.isAllowed("https://evil.example.com/cb")).isFalse();
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
    void customSchemeEntryWithAuthorityRequiresExactAuthority() {
        final RedirectAllowlistProperties props =
                new RedirectAllowlistProperties(List.of("cursor://auth/callback"));
        assertThat(props.isAllowed("cursor://auth/callback")).isTrue();
        assertThat(props.isAllowed("cursor://auth/callback/done")).isTrue();
        assertThat(props.isAllowed("cursor://other/callback")).isFalse();
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
