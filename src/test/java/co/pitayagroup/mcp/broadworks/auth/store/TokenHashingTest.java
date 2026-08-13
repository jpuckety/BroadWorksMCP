package co.pitayagroup.mcp.broadworks.auth.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TokenHashingTest {

    @Test
    void producesAStableUrlSafeDigest() {
        final String digest = TokenHashing.sha256("opaque-access-token");

        assertThat(digest)
                .isEqualTo(TokenHashing.sha256("opaque-access-token"))
                .doesNotContain("opaque-access-token")
                .matches("[A-Za-z0-9_-]{43}");
    }

    @Test
    void distinctTokensProduceDistinctDigests() {
        assertThat(TokenHashing.sha256("token-a")).isNotEqualTo(TokenHashing.sha256("token-b"));
    }

    @Test
    void passesThroughNull() {
        assertThat(TokenHashing.sha256(null)).isNull();
    }
}
