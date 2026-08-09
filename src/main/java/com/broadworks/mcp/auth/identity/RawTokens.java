package com.broadworks.mcp.auth.identity;

import java.time.Instant;

/**
 * Raw tokens returned by the upstream IdP token endpoint.
 *
 * @param idToken              the raw (compact JWS) ID token.
 * @param accessToken          the IdP access token (optional; may be forwarded downstream).
 * @param refreshToken         the IdP refresh token (optional).
 * @param accessTokenExpiresAt when the IdP access token expires (optional).
 */
public record RawTokens(
        String idToken,
        String accessToken,
        String refreshToken,
        Instant accessTokenExpiresAt
) {
}
