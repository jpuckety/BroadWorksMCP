package co.pitayagroup.mcp.broadworks.auth.store.inmemory;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import co.pitayagroup.mcp.broadworks.auth.store.RegisteredClientRecord;
import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;

/**
 * In-memory {@link SessionStore} backed by concurrent maps.
 *
 * <p>Non-durable and single-node only: state is lost on restart and not shared across replicas.
 * Intended for local / stdio / test use.</p>
 */
public class InMemorySessionStore implements SessionStore {

    private final ConcurrentMap<String, Session> sessionsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionIdByAccessToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionIdByRefreshToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> sessionIdByAuthorizationId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, RegisteredClientRecord> clientsById = new ConcurrentHashMap<>();

    @Override
    public Session createSession(Session session) {
        if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
            final String previous = sessionIdByAuthorizationId.put(session.authorizationId(), session.sessionId());
            if (previous != null && !previous.equals(session.sessionId())) {
                deleteSession(previous);
            }
        }
        sessionsById.put(session.sessionId(), session);
        sessionIdByAccessToken.put(session.accessToken(), session.sessionId());
        if (session.refreshToken() != null) {
            sessionIdByRefreshToken.put(session.refreshToken(), session.sessionId());
        }
        return session;
    }

    @Override
    public Optional<Session> getSessionByAccessToken(String accessToken) {
        if (accessToken == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionIdByAccessToken.get(accessToken)).map(sessionsById::get);
    }

    @Override
    public Optional<Session> getSessionByRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(sessionIdByRefreshToken.get(refreshToken)).map(sessionsById::get);
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        final Session removed = sessionsById.remove(sessionId);
        if (removed != null) {
            sessionIdByAccessToken.remove(removed.accessToken());
            if (removed.refreshToken() != null) {
                sessionIdByRefreshToken.remove(removed.refreshToken());
            }
            if (removed.authorizationId() != null) {
                sessionIdByAuthorizationId.remove(removed.authorizationId(), sessionId);
            }
        }
    }

    @Override
    public void deleteSessionsByAuthorizationId(String authorizationId) {
        if (authorizationId == null) {
            return;
        }
        final String sessionId = sessionIdByAuthorizationId.remove(authorizationId);
        if (sessionId != null) {
            deleteSession(sessionId);
        }
    }

    @Override
    public void saveClient(RegisteredClientRecord client) {
        clientsById.put(client.clientId(), client);
    }

    @Override
    public Optional<RegisteredClientRecord> getClient(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        final RegisteredClientRecord client = clientsById.get(clientId);
        if (client == null) {
            return Optional.empty();
        }
        if (client.expiresAt() != null && Instant.now().isAfter(client.expiresAt())) {
            clientsById.remove(clientId, client);
            return Optional.empty();
        }
        return Optional.of(client);
    }
}
