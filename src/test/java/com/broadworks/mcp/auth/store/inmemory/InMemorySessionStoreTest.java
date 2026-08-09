package com.broadworks.mcp.auth.store.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.broadworks.mcp.auth.store.RegisteredClientRecord;
import com.broadworks.mcp.auth.store.Session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    private InMemorySessionStore store;

    @BeforeEach
    void setUp() {
        store = new InMemorySessionStore();
    }

    private Session session(String access, String refresh, String subject) {
        final Instant now = Instant.now();
        return new Session(null, access, refresh, "client-1", subject, subject + "@example.com",
                "id-token", "idp-refresh", now.plus(Duration.ofHours(1)),
                now.plus(Duration.ofDays(30)), now);
    }

    @Test
    void createAndLookupByAccessToken() {
        store.createSession(session("access-1", "refresh-1", "sub-1"));

        assertThat(store.getSessionByAccessToken("access-1")).isPresent()
                .get().extracting(Session::subject).isEqualTo("sub-1");
        assertThat(store.getSessionByAccessToken("missing")).isEmpty();
    }

    @Test
    void lookupByRefreshToken() {
        store.createSession(session("access-2", "refresh-2", "sub-2"));

        assertThat(store.getSessionByRefreshToken("refresh-2")).isPresent()
                .get().extracting(Session::accessToken).isEqualTo("access-2");
        assertThat(store.getSessionByRefreshToken("missing")).isEmpty();
    }

    @Test
    void deleteRemovesAllIndexes() {
        final Session created = store.createSession(session("access-3", "refresh-3", "sub-3"));

        store.deleteSession(created.sessionId());

        assertThat(store.getSessionByAccessToken("access-3")).isEmpty();
        assertThat(store.getSessionByRefreshToken("refresh-3")).isEmpty();
    }

    @Test
    void sessionIdDefaultsToAccessToken() {
        final Session created = store.createSession(session("access-4", "refresh-4", "sub-4"));
        assertThat(created.sessionId()).isEqualTo("access-4");
    }

    @Test
    void saveAndGetClient() {
        final RegisteredClientRecord client = new RegisteredClientRecord(
                "client-xyz", "Test Client",
                List.of("https://app.example.com/cb"),
                List.of("openid", "email"),
                List.of("authorization_code", "refresh_token"),
                null, Instant.now(), Instant.now().plus(Duration.ofDays(90)));

        store.saveClient(client);

        assertThat(store.getClient("client-xyz")).isPresent()
                .get().satisfies(c -> {
                    assertThat(c.clientName()).isEqualTo("Test Client");
                    assertThat(c.tokenEndpointAuthMethod()).isEqualTo("none");
                    assertThat(c.grantTypes()).contains("refresh_token");
                });
        assertThat(store.getClient("nope")).isEmpty();
    }
}
