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
}
