package co.pitayagroup.mcp.broadworks.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class AuthTokenPropertiesTest {

    @Test
    void appliesBlueprintDefaultsWhenUnset() {
        final AuthTokenProperties properties = new AuthTokenProperties(null, null, null, null, null);

        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(properties.authorizationCodeTtl()).isEqualTo(Duration.ofMinutes(5));
        assertThat(properties.pendingAuthorizationTtl()).isEqualTo(Duration.ofMinutes(15));
        assertThat(properties.registeredClientTtl()).isEqualTo(Duration.ofDays(90));
    }

    @Test
    void keepsExplicitValues() {
        final AuthTokenProperties properties = new AuthTokenProperties(
                Duration.ofMinutes(30), Duration.ofDays(7), Duration.ofMinutes(2),
                Duration.ofMinutes(10), Duration.ofDays(45));

        assertThat(properties.accessTokenTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(properties.refreshTokenTtl()).isEqualTo(Duration.ofDays(7));
        assertThat(properties.registeredClientTtl()).isEqualTo(Duration.ofDays(45));
    }
}
