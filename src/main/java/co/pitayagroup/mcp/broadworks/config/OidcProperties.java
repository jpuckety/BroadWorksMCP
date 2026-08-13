package co.pitayagroup.mcp.broadworks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Upstream OpenID Connect provider settings (Google by default).
 *
 * <p>Secrets are supplied via environment / SSM and never hard-coded. For public desktop clients a
 * client secret may be absent; it is only required for the confidential server-side leg of the
 * authorization-code exchange.</p>
 *
 * @param issuerUri     OIDC issuer URI (e.g. {@code https://accounts.google.com}); used for JWKS and
 *                      discovery.
 * @param clientId      OAuth client id registered with the upstream IdP.
 * @param clientSecret  OAuth client secret (may be blank for public-only setups).
 * @param scopes        space-free list of scopes requested from the IdP.
 */
@ConfigurationProperties(prefix = "broadworks.oidc")
public record OidcProperties(
        String issuerUri,
        String clientId,
        String clientSecret,
        java.util.List<String> scopes
) {
    public OidcProperties {
        if (issuerUri == null || issuerUri.isBlank()) {
            issuerUri = "https://accounts.google.com";
        }
        if (scopes == null || scopes.isEmpty()) {
            scopes = java.util.List.of("openid", "email", "profile");
        }
    }
}
