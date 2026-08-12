package com.broadworks.mcp.auth.store.inmemory;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.broadworks.mcp.auth.store.AuthorizationStore;

import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * Process-local {@link AuthorizationStore} for tests and {@code IN_MEMORY} backend.
 */
public class InMemoryAuthorizationStore implements AuthorizationStore {

    private final ConcurrentMap<String, OAuth2Authorization> byId = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByAccessToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByRefreshToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByCode = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByState = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, String> idByIdToken = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, OAuth2AuthorizationConsent> consents = new ConcurrentHashMap<>();

    @Override
    public void saveAuthorization(OAuth2Authorization authorization) {
        final OAuth2Authorization existing = byId.get(authorization.getId());
        if (existing != null) {
            clearIndexes(existing);
        }
        byId.put(authorization.getId(), authorization);
        index(authorization);
    }

    @Override
    public void removeAuthorization(OAuth2Authorization authorization) {
        if (authorization == null) {
            return;
        }
        final OAuth2Authorization removed = byId.remove(authorization.getId());
        clearIndexes(removed != null ? removed : authorization);
    }

    @Override
    public Optional<OAuth2Authorization> findAuthorizationById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public Optional<OAuth2Authorization> findAuthorizationByToken(String token, @Nullable OAuth2TokenType tokenType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (tokenType == null) {
            return findByAnyToken(token);
        }
        final String type = tokenType.getValue();
        if ("state".equals(type)) {
            return resolve(idByState.get(token));
        }
        if ("code".equals(type)) {
            return resolve(idByCode.get(token));
        }
        if (OAuth2TokenType.ACCESS_TOKEN.getValue().equals(type)) {
            return resolve(idByAccessToken.get(token));
        }
        if (OAuth2TokenType.REFRESH_TOKEN.getValue().equals(type)) {
            return resolve(idByRefreshToken.get(token));
        }
        if ("id_token".equals(type)) {
            return resolve(idByIdToken.get(token));
        }
        return findByAnyToken(token);
    }

    @Override
    public void saveConsent(OAuth2AuthorizationConsent consent) {
        consents.put(consentKey(consent.getRegisteredClientId(), consent.getPrincipalName()), consent);
    }

    @Override
    public void removeConsent(OAuth2AuthorizationConsent consent) {
        if (consent == null) {
            return;
        }
        consents.remove(consentKey(consent.getRegisteredClientId(), consent.getPrincipalName()));
    }

    @Override
    public Optional<OAuth2AuthorizationConsent> findConsent(String registeredClientId, String principalName) {
        if (registeredClientId == null || principalName == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(consents.get(consentKey(registeredClientId, principalName)));
    }

    private Optional<OAuth2Authorization> findByAnyToken(String token) {
        return resolve(idByState.get(token))
                .or(() -> resolve(idByCode.get(token)))
                .or(() -> resolve(idByAccessToken.get(token)))
                .or(() -> resolve(idByRefreshToken.get(token)))
                .or(() -> resolve(idByIdToken.get(token)));
    }

    private Optional<OAuth2Authorization> resolve(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    private void index(OAuth2Authorization authorization) {
        final String id = authorization.getId();
        final String state = authorization.getAttribute("state");
        if (state != null && !state.isBlank()) {
            idByState.put(state, id);
        }
        final OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
                authorization.getToken(OAuth2AuthorizationCode.class);
        if (code != null) {
            idByCode.put(code.getToken().getTokenValue(), id);
        }
        final OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        if (access != null) {
            idByAccessToken.put(access.getToken().getTokenValue(), id);
        }
        final OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        if (refresh != null) {
            idByRefreshToken.put(refresh.getToken().getTokenValue(), id);
        }
        final OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken != null) {
            idByIdToken.put(idToken.getToken().getTokenValue(), id);
        }
    }

    private void clearIndexes(OAuth2Authorization authorization) {
        final String state = authorization.getAttribute("state");
        if (state != null) {
            idByState.remove(state, authorization.getId());
        }
        final OAuth2Authorization.Token<OAuth2AuthorizationCode> code =
                authorization.getToken(OAuth2AuthorizationCode.class);
        if (code != null) {
            idByCode.remove(code.getToken().getTokenValue(), authorization.getId());
        }
        final OAuth2Authorization.Token<OAuth2AccessToken> access = authorization.getAccessToken();
        if (access != null) {
            idByAccessToken.remove(access.getToken().getTokenValue(), authorization.getId());
        }
        final OAuth2Authorization.Token<OAuth2RefreshToken> refresh = authorization.getRefreshToken();
        if (refresh != null) {
            idByRefreshToken.remove(refresh.getToken().getTokenValue(), authorization.getId());
        }
        final OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken != null) {
            idByIdToken.remove(idToken.getToken().getTokenValue(), authorization.getId());
        }
    }

    private static String consentKey(String clientId, String principal) {
        return clientId + "|" + principal;
    }
}
