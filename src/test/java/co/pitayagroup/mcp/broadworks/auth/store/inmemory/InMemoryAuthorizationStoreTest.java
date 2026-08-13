package co.pitayagroup.mcp.broadworks.auth.store.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;

/**
 * Verifies durable-style authorization lookups work across a shared in-memory store
 * (models multi-instance findByToken for codes / refresh tokens).
 */
class InMemoryAuthorizationStoreTest {

    private InMemoryAuthorizationStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryAuthorizationStore();
    }

    @Test
    void saveAndFindByCodeAndRefreshToken() {
        final RegisteredClient client = RegisteredClient.withId("client-1")
                .clientId("client-1")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri("http://127.0.0.1/cb")
                .scope("openid")
                .build();

        final Instant now = Instant.now();
        final OAuth2AuthorizationCode code = new OAuth2AuthorizationCode("code-abc", now, now.plusSeconds(300));
        final OAuth2AccessToken access = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, "access-xyz", now, now.plusSeconds(3600));
        final OAuth2RefreshToken refresh = new OAuth2RefreshToken("refresh-xyz", now, now.plusSeconds(86400));

        final OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(client)
                .id("authz-1")
                .principalName("user-sub")
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .token(code)
                .accessToken(access)
                .refreshToken(refresh)
                .attribute("state", "state-1")
                .build();

        store.saveAuthorization(authorization);

        assertThat(store.findAuthorizationById("authz-1")).isPresent();
        assertThat(store.findAuthorizationByToken("code-abc", new OAuth2TokenType("code")))
                .isPresent()
                .get()
                .extracting(OAuth2Authorization::getId)
                .isEqualTo("authz-1");
        assertThat(store.findAuthorizationByToken("refresh-xyz", OAuth2TokenType.REFRESH_TOKEN))
                .isPresent();
        assertThat(store.findAuthorizationByToken("access-xyz", OAuth2TokenType.ACCESS_TOKEN))
                .isPresent();
        assertThat(store.findAuthorizationByToken("state-1", new OAuth2TokenType("state")))
                .isPresent();
    }
}
