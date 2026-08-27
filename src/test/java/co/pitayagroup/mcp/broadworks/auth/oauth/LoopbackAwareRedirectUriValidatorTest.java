package co.pitayagroup.mcp.broadworks.auth.oauth;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class LoopbackAwareRedirectUriValidatorTest {

    @Test
    void localhostIgnoresEphemeralPort() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://localhost:56056/callback",
                List.of("http://localhost:1111/callback"))).isTrue();
    }

    @Test
    void loopbackIpIgnoresEphemeralPort() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://127.0.0.1:56056/callback",
                List.of("http://127.0.0.1:1111/callback"))).isTrue();
    }

    @Test
    void pathMustStillMatch() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://localhost:56056/other",
                List.of("http://localhost:1111/callback"))).isFalse();
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://localhost:56056/other",
                List.of("http://127.0.0.1:1111/callback"))).isFalse();
    }

    @Test
    void localhostMatchesLoopbackIpIgnoringPort() {
        // Desktop MCP clients often register 127.0.0.1 (RFC 8252) then authorize with localhost.
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://localhost:56056/callback",
                List.of("http://127.0.0.1:1111/callback"))).isTrue();
    }

    @Test
    void loopbackIpMatchesLocalhostIgnoringPort() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://127.0.0.1:56056/callback",
                List.of("http://localhost:1111/callback"))).isTrue();
    }

    @Test
    void ipv6LoopbackMatchesLocalhostIgnoringPort() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://[::1]:56056/callback",
                List.of("http://localhost:1111/callback"))).isTrue();
    }

    @Test
    void nonLoopbackHostIsNotMatchedHere() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "https://app.example.com/callback",
                List.of("https://app.example.com/callback"))).isFalse();
    }

    @Test
    void fragmentIsRejected() {
        assertThat(LoopbackAwareRedirectUriValidator.matchesRegisteredLoopbackIgnoringPort(
                "http://localhost:56056/callback#frag",
                List.of("http://localhost:1111/callback"))).isFalse();
    }
}
