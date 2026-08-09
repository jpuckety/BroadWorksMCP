package com.broadworks.mcp.auth.store;

import java.time.Instant;
import java.util.List;

/**
 * A dynamically registered OAuth client (RFC 7591). Only public clients are supported, so no client
 * secret is ever stored.
 *
 * @param clientId                 generated client identifier.
 * @param clientName               human-readable client name.
 * @param redirectUris             registered redirect URIs.
 * @param scopes                   allowed scopes.
 * @param grantTypes               allowed grant types (e.g. {@code authorization_code},
 *                                 {@code refresh_token}).
 * @param tokenEndpointAuthMethod  always {@code none} for public clients.
 * @param createdAt                registration timestamp.
 * @param expiresAt                registration expiry (default 90 days after creation).
 */
public record RegisteredClientRecord(
        String clientId,
        String clientName,
        List<String> redirectUris,
        List<String> scopes,
        List<String> grantTypes,
        String tokenEndpointAuthMethod,
        Instant createdAt,
        Instant expiresAt
) {
    /** Token-endpoint auth method for public clients. */
    public static final String PUBLIC_CLIENT_AUTH_METHOD = "none";

    public RegisteredClientRecord {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId is required");
        }
        redirectUris = redirectUris == null ? List.of() : List.copyOf(redirectUris);
        scopes = scopes == null ? List.of() : List.copyOf(scopes);
        grantTypes = grantTypes == null ? List.of() : List.copyOf(grantTypes);
        if (tokenEndpointAuthMethod == null || tokenEndpointAuthMethod.isBlank()) {
            tokenEndpointAuthMethod = PUBLIC_CLIENT_AUTH_METHOD;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
