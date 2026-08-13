package co.pitayagroup.mcp.broadworks.auth.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.store.RegisteredClientRecord;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemorySessionStore;
import co.pitayagroup.mcp.broadworks.config.AuthTokenProperties;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;

class StoreBackedRegisteredClientRepositoryTest {

    private static final AuthTokenProperties TOKEN_PROPERTIES =
            new AuthTokenProperties(null, null, null, null, null);

    @Test
    void materialisesStoredClientsAsPublicPkceOpaqueTokenClients() {
        final InMemorySessionStore sessionStore = new InMemorySessionStore();
        sessionStore.saveClient(record("client-1", Instant.now().plus(Duration.ofDays(90))));

        final var client = repository(sessionStore).findByClientId("client-1");

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getClientSecret()).isNull();
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getTokenSettings().getAccessTokenFormat()).isEqualTo(OAuth2TokenFormat.REFERENCE);
    }

    @Test
    void rejectsExpiredRegistrationsEvenWhenTheStoreStillReturnsThem() {
        // DynamoDB TTL deletion is best-effort and can lag by ~48h, so the repository must not rely
        // on the store having already dropped the item. The stubbed store models that lag.
        final SessionStore laggingStore = mock(SessionStore.class);
        when(laggingStore.getClient(anyString()))
                .thenReturn(Optional.of(record("stale-client", Instant.now().minus(Duration.ofMinutes(1)))));

        final var repository = repository(laggingStore);

        assertThat(repository.findByClientId("stale-client")).isNull();
        assertThat(repository.findById("stale-client")).isNull();
    }

    @Test
    void acceptsRegistrationsWithoutAnExpiry() {
        final SessionStore store = mock(SessionStore.class);
        when(store.getClient(anyString())).thenReturn(Optional.of(record("eternal-client", null)));

        assertThat(repository(store).findByClientId("eternal-client")).isNotNull();
    }

    private static StoreBackedRegisteredClientRepository repository(SessionStore sessionStore) {
        return new StoreBackedRegisteredClientRepository(sessionStore, TOKEN_PROPERTIES);
    }

    private static RegisteredClientRecord record(String clientId, Instant expiresAt) {
        return new RegisteredClientRecord(
                clientId,
                "Test App",
                List.of("https://app.example.com/callback"),
                List.of("openid", "email"),
                List.of("authorization_code", "refresh_token"),
                RegisteredClientRecord.PUBLIC_CLIENT_AUTH_METHOD,
                Instant.now().minus(Duration.ofMinutes(5)),
                expiresAt);
    }
}
