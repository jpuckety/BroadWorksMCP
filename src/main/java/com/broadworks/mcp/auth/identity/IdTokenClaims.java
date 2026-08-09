package com.broadworks.mcp.auth.identity;

import java.time.Instant;

/**
 * The verified subset of upstream IdP ID-token claims the server relies on.
 *
 * @param sub           the canonical per-tenant user id ({@code sub}); never {@code null}/blank.
 * @param email         the user's email.
 * @param emailVerified whether the IdP marked the email as verified (must be {@code true} to sign in).
 * @param iss           the token issuer.
 * @param aud           the token audience (this server's IdP client id).
 * @param exp           the ID-token expiry (used to cap the issued access-token lifetime).
 */
public record IdTokenClaims(
        String sub,
        String email,
        boolean emailVerified,
        String iss,
        String aud,
        Instant exp
) {
}
