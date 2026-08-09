package com.broadworks.mcp.auth.identity;

import java.net.URI;

/**
 * Provider-agnostic abstraction over an upstream OpenID Connect identity provider.
 *
 * <p>The default reference implementation targets Google; a test stub is used in tests. The server
 * never stores end-user passwords — it only drives the authorization-code + PKCE flow and verifies
 * the returned ID token.</p>
 */
public interface IdentityProvider {

    /**
     * Builds the upstream authorization-request URL (authorization-code + PKCE, {@code S256}).
     *
     * @param state         opaque anti-forgery / correlation value echoed back on the callback.
     * @param codeChallenge the S256 PKCE code challenge.
     * @return the absolute URL the user agent should be redirected to.
     */
    URI authCodeUrl(String state, String codeChallenge);

    /**
     * Exchanges an authorization code for tokens at the IdP token endpoint and verifies the returned
     * ID token.
     *
     * @param code         the authorization code returned to the callback.
     * @param codeVerifier the PKCE code verifier matching the earlier challenge.
     * @return the verified claims and raw tokens.
     * @throws IdentityProviderException if the exchange fails or the ID token is invalid.
     */
    ExchangeResult exchange(String code, String codeVerifier);

    /**
     * Fully verifies a raw ID token: signature (via JWKS), issuer, audience, expiry, {@code sub}
     * presence and {@code email_verified == true}.
     *
     * @param rawIdToken the compact-serialized ID token.
     * @return the verified claims.
     * @throws IdentityProviderException if verification fails.
     */
    IdTokenClaims verifyIdToken(String rawIdToken);
}
