package com.broadworks.mcp.auth.store;

import java.util.Optional;

/**
 * Durable store for OAuth sessions and dynamically registered clients.
 *
 * <p>Authorization codes and pending authorizations are intentionally NOT part of this interface:
 * they are short-lived and remain process-local.</p>
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
     * Persists (creates or replaces) a registered client.
     */
    void saveClient(RegisteredClientRecord client);

    /**
     * @return the registered client with the given id, if present.
     */
    Optional<RegisteredClientRecord> getClient(String clientId);
}
