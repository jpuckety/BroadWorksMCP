package com.broadworks.mcp.auth.session;

import com.broadworks.mcp.auth.store.AuthorizationStore;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;

/**
 * Durable {@link OAuth2AuthorizationConsentService} backed by {@link AuthorizationStore}.
 */
@RequiredArgsConstructor
public class StoreBackedAuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private final AuthorizationStore authorizationStore;

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        authorizationStore.saveConsent(authorizationConsent);
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        authorizationStore.removeConsent(authorizationConsent);
    }

    @Override
    public OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        return authorizationStore.findConsent(registeredClientId, principalName).orElse(null);
    }
}
