package com.broadworks.mcp.auth.store;

import java.util.Optional;

import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Durable store for Spring Authorization Server authorizations (codes, tokens, state) and consents.
 *
 * <p>Shared across ECS tasks so authorize-on-A / token-exchange-on-B works without ALB stickiness.</p>
 */
public interface AuthorizationStore {

    void saveAuthorization(OAuth2Authorization authorization);

    void removeAuthorization(OAuth2Authorization authorization);

    Optional<OAuth2Authorization> findAuthorizationById(String id);

    Optional<OAuth2Authorization> findAuthorizationByToken(String token, @Nullable OAuth2TokenType tokenType);

    void saveConsent(OAuth2AuthorizationConsent consent);

    void removeConsent(OAuth2AuthorizationConsent consent);

    Optional<OAuth2AuthorizationConsent> findConsent(String registeredClientId, String principalName);
}
