package com.broadworks.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configurable token / session lifetimes for the OAuth 2.1 Authorization Server.
 *
 * <p>All lifetimes are expressed as {@link Duration} and default to the blueprint values; there are
 * no magic numbers elsewhere in the code. The access-token lifetime is additionally capped at
 * runtime by the upstream IdP ID-token expiry.</p>
 *
 * @param accessTokenTtl        opaque access token lifetime (default 1 hour).
 * @param refreshTokenTtl       opaque refresh token lifetime (default 30 days).
 * @param authorizationCodeTtl  one-time authorization code lifetime (default 5 minutes).
 * @param pendingAuthorizationTtl server-side pending-authorization state lifetime (default 15 minutes).
 * @param registeredClientTtl   dynamically registered client lifetime (default 90 days).
 */
@ConfigurationProperties(prefix = "broadworks.auth.token")
public record AuthTokenProperties(
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        Duration authorizationCodeTtl,
        Duration pendingAuthorizationTtl,
        Duration registeredClientTtl
) {
    /** Default opaque access token lifetime. */
    public static final Duration DEFAULT_ACCESS_TOKEN_TTL = Duration.ofHours(1);
    /** Default opaque refresh token lifetime. */
    public static final Duration DEFAULT_REFRESH_TOKEN_TTL = Duration.ofDays(30);
    /** Default one-time authorization code lifetime. */
    public static final Duration DEFAULT_AUTHORIZATION_CODE_TTL = Duration.ofMinutes(5);
    /** Default pending-authorization server-side state lifetime. */
    public static final Duration DEFAULT_PENDING_AUTHORIZATION_TTL = Duration.ofMinutes(15);
    /** Default dynamically registered client lifetime. */
    public static final Duration DEFAULT_REGISTERED_CLIENT_TTL = Duration.ofDays(90);

    public AuthTokenProperties {
        if (accessTokenTtl == null) {
            accessTokenTtl = DEFAULT_ACCESS_TOKEN_TTL;
        }
        if (refreshTokenTtl == null) {
            refreshTokenTtl = DEFAULT_REFRESH_TOKEN_TTL;
        }
        if (authorizationCodeTtl == null) {
            authorizationCodeTtl = DEFAULT_AUTHORIZATION_CODE_TTL;
        }
        if (pendingAuthorizationTtl == null) {
            pendingAuthorizationTtl = DEFAULT_PENDING_AUTHORIZATION_TTL;
        }
        if (registeredClientTtl == null) {
            registeredClientTtl = DEFAULT_REGISTERED_CLIENT_TTL;
        }
    }
}
