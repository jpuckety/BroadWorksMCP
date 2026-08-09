package com.broadworks.mcp.auth.identity;

/**
 * The result of exchanging an authorization code at the upstream IdP: the verified ID-token claims
 * plus the raw tokens.
 *
 * @param claims the verified {@link IdTokenClaims}.
 * @param tokens the {@link RawTokens} returned by the IdP.
 */
public record ExchangeResult(IdTokenClaims claims, RawTokens tokens) {
}
