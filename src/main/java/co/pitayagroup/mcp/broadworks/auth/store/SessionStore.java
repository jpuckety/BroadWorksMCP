package co.pitayagroup.mcp.broadworks.auth.store;

import java.util.Optional;

/**
 * Durable store for OAuth sessions and dynamically registered clients.
 *
 * <p>Authorization codes and pending authorizations live in {@link AuthorizationStore}, not here.
 * This store holds the opaque access/refresh token sessions the Resource Server introspects.</p>
 */
public interface SessionStore {

    /**
     * Persists a new session.
     *
     * @param session the session to store (its {@code sessionId} defaults to the access token).
     * @return the stored session.
     */
    Session createSession(Session session);

    /**
     * @return the session bound to the given opaque access token, if present and known.
     */
    Optional<Session> getSessionByAccessToken(String accessToken);

    /**
     * @return the session bound to the given opaque refresh token, if present and known.
     */
    Optional<Session> getSessionByRefreshToken(String refreshToken);

    /**
     * Deletes the session with the given identifier. No-op if it does not exist.
     */
    void deleteSession(String sessionId);

    /**
     * Deletes every session issued under the given SAS authorization id (token rotation).
     * No-op if none exist.
     */
    void deleteSessionsByAuthorizationId(String authorizationId);

    /**
     * Persists (creates or replaces) a registered client.
     */
    void saveClient(RegisteredClientRecord client);

    /**
     * @return the registered client with the given id, if present and not expired.
     */
    Optional<RegisteredClientRecord> getClient(String clientId);
}
