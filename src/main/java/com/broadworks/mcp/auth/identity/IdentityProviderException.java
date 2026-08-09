package com.broadworks.mcp.auth.identity;

/**
 * Raised when an upstream IdP interaction fails: token exchange errors, ID-token verification
 * failures ({@code email_verified == false}, bad signature/issuer/audience, expiry), or discovery
 * problems.
 *
 * <p>Messages are safe for server-side logging and never contain raw tokens or secrets.</p>
 */
public class IdentityProviderException extends RuntimeException {

    public IdentityProviderException(String message) {
        super(message);
    }

    public IdentityProviderException(String message, Throwable cause) {
        super(message, cause);
    }
}
