package com.broadworks.mcp.auth.session;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OpaqueTokenFactoryTest {

    private final OpaqueTokenFactory factory = new OpaqueTokenFactory();

    @Test
    void producesAtLeast32RandomBytes() {
        final String token = factory.create();
        final byte[] decoded = Base64.getUrlDecoder().decode(token);
        assertThat(decoded).hasSizeGreaterThanOrEqualTo(OpaqueTokenFactory.TOKEN_BYTES);
    }

    @Test
    void producesUrlSafeUnpaddedValue() {
        final String token = factory.create();
        assertThat(token).doesNotContain("=", "+", "/");
    }

    @Test
    void producesUniqueTokens() {
        final Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            tokens.add(factory.create());
        }
        assertThat(tokens).hasSize(1000);
    }
}
