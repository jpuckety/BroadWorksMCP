package com.broadworks.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PublicBaseUrlPropertiesTest {

    @Test
    void mcpResourceUrlUsesBaseAndMcpPath() {
        assertThat(new PublicBaseUrlProperties("").mcpResourceUrl())
                .isEqualTo("http://localhost:8080/mcp");
        assertThat(new PublicBaseUrlProperties("broadworks.mcp.ecg.co").mcpResourceUrl())
                .isEqualTo("https://broadworks.mcp.ecg.co/mcp");
    }

    @Test
    void callbackUriUsesSpringGoogleLoginPath() {
        assertThat(new PublicBaseUrlProperties("example.com").callbackUri())
                .isEqualTo("https://example.com/login/oauth2/code/google");
    }

    @Test
    void resourceMatchesIgnoresTrailingSlashAndCase() {
        assertThat(PublicBaseUrlProperties.resourceMatches(
                "https://Example.com/mcp/", "https://example.com/mcp")).isTrue();
        assertThat(PublicBaseUrlProperties.resourceMatches(
                "https://other.example/mcp", "https://example.com/mcp")).isFalse();
        assertThat(PublicBaseUrlProperties.resourceMatches(null, "https://example.com/mcp")).isFalse();
    }
}
