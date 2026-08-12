package com.broadworks.mcp.auth.store;

import java.time.Instant;

/**
 * A persisted OAuth session created after a successful authorization-code exchange.
 *
 * <p>Keyed by {@link #sessionId()} (which, in the opaque-token model, equals the current access
 * token). All per-user state is keyed by {@link #subject()} for strict tenant isolation; the
 * {@link #email()} is informational only and never used as a key.</p>
 *
 * @param sessionId              opaque session identifier (equals the access token in this impl).
 * @param accessToken            opaque bearer access token presented to the Resource Server.
 * @param refreshToken           opaque refresh token exchanged at {@code /oauth2/token}.
 * @param clientId               id of the registered client the tokens were issued to.
 * @param subject                upstream IdP {@code sub}; the canonical per-tenant user id.
 * @param email                  upstream IdP verified email (informational only).
 * @param idToken                verified upstream IdP ID token (optional, for downstream use).
 * @param idpRefreshToken        upstream IdP refresh token (optional).
 * @param accessTokenExpiresAt   access-token expiry (capped by the IdP ID-token expiry).
 * @param refreshTokenExpiresAt  refresh-token expiry.
 * @param createdAt              session creation timestamp.
 * @param authorizationId        SAS {@code OAuth2Authorization} id this session was issued from;
 *                               used to invalidate prior access-token sessions on rotation.
 * @param audience               RFC 8707 resource audience this access token is bound to
 *                               (canonical MCP resource URL).
 */
public record Session(
        String sessionId,
        String accessToken,
        String refreshToken,
        String clientId,
        String subject,
        String email,
        String idToken,
        String idpRefreshToken,
        Instant accessTokenExpiresAt,
        Instant refreshTokenExpiresAt,
        Instant createdAt,
        String authorizationId,
        String audience
) {
    public Session {
        if (accessToken == null || accessToken.isBlank()) {
            throw new IllegalArgumentException("accessToken is required");
        }
        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("subject is required");
        }
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = accessToken;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
