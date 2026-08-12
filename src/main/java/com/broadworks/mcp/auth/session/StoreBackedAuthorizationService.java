package com.broadworks.mcp.auth.session;

import java.security.Principal;
import java.time.Instant;

import com.broadworks.mcp.auth.store.AuthorizationStore;
import com.broadworks.mcp.auth.store.Session;
import com.broadworks.mcp.auth.store.SessionStore;
import com.broadworks.mcp.config.PublicBaseUrlProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.util.StringUtils;

/**
 * {@link OAuth2AuthorizationService} backed by a durable {@link AuthorizationStore} (shared across
 * ECS tasks) that also syncs issued access tokens into {@link SessionStore} for Resource Server
 * introspection.
 *
 * <p>On access-token issue or rotation, prior sessions for the same authorization id are deleted so
 * old opaque access tokens stop validating immediately.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class StoreBackedAuthorizationService implements OAuth2AuthorizationService {

    /** RFC 8707 resource indicator parameter name. */
    public static final String RESOURCE_PARAMETER = "resource";

    private final AuthorizationStore authorizationStore;
    private final SessionStore sessionStore;
    private final PublicBaseUrlProperties publicBaseUrl;

    @Override
    public void save(OAuth2Authorization authorization) {
        authorizationStore.saveAuthorization(authorization);
        syncSession(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        authorizationStore.removeAuthorization(authorization);
        sessionStore.deleteSessionsByAuthorizationId(authorization.getId());
        final OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null) {
            sessionStore.deleteSession(accessToken.getToken().getTokenValue());
        }
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return authorizationStore.findAuthorizationById(id).orElse(null);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return authorizationStore.findAuthorizationByToken(token, tokenType).orElse(null);
    }

    private void syncSession(OAuth2Authorization authorization) {
        final OAuth2Authorization.Token<OAuth2AccessToken> accessTokenHolder = authorization.getAccessToken();
        if (accessTokenHolder == null) {
            return; // No access token yet (e.g. authorization-code step).
        }
        final OAuth2AccessToken accessToken = accessTokenHolder.getToken();
        final OAuth2Authorization.Token<OAuth2RefreshToken> refreshHolder = authorization.getRefreshToken();
        final String refreshToken = refreshHolder != null ? refreshHolder.getToken().getTokenValue() : null;
        final Instant refreshExpiresAt = refreshHolder != null ? refreshHolder.getToken().getExpiresAt() : null;

        final String subject = authorization.getPrincipalName();
        final String email = resolveEmail(authorization);
        final String idToken = resolveIdToken(authorization);
        final String audience = resolveAudience(authorization);

        // Invalidate any prior access-token session for this authorization (token rotation).
        sessionStore.deleteSessionsByAuthorizationId(authorization.getId());

        final Session session = new Session(
                accessToken.getTokenValue(),
                accessToken.getTokenValue(),
                refreshToken,
                authorization.getRegisteredClientId(),
                subject,
                email,
                idToken,
                null,
                accessToken.getExpiresAt(),
                refreshExpiresAt,
                Instant.now(),
                authorization.getId(),
                audience);
        sessionStore.createSession(session);
        log.debug("Persisted session for subject={} client={} audience={}",
                subject, authorization.getRegisteredClientId(), audience);
    }

    /**
     * Always bind issued tokens to the canonical MCP resource. Foreign {@code resource} values are
     * rejected at authorize time; this is a defense-in-depth bind at session write.
     */
    String resolveAudience(OAuth2Authorization authorization) {
        final String canonical = publicBaseUrl.mcpResourceUrl();
        final String requested = extractResourceParameter(authorization);
        if (requested != null && !PublicBaseUrlProperties.resourceMatches(requested, canonical)) {
            log.warn("Authorization {} requested foreign resource={} (canonical={}); binding to canonical",
                    authorization.getId(), requested, canonical);
        }
        return canonical;
    }

    @Nullable
    static String extractResourceParameter(OAuth2Authorization authorization) {
        final OAuth2AuthorizationRequest authRequest =
                authorization.getAttribute(OAuth2AuthorizationRequest.class.getName());
        if (authRequest != null) {
            final Object fromRequest = authRequest.getAdditionalParameters().get(RESOURCE_PARAMETER);
            if (fromRequest != null && StringUtils.hasText(fromRequest.toString())) {
                return fromRequest.toString();
            }
        }
        final Object attr = authorization.getAttribute(RESOURCE_PARAMETER);
        if (attr != null && StringUtils.hasText(attr.toString())) {
            return attr.toString();
        }
        return null;
    }

    private String resolveEmail(OAuth2Authorization authorization) {
        final OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        if (idToken != null) {
            final Object email = idToken.getToken().getClaims().get(UserInfo.EMAIL_ATTRIBUTE);
            if (email != null) {
                return email.toString();
            }
        }
        final Authentication authentication = authorization.getAttribute(Principal.class.getName());
        if (authentication != null && authentication.getPrincipal() instanceof OAuth2User user) {
            final Object email = user.getAttributes().get(UserInfo.EMAIL_ATTRIBUTE);
            return email != null ? email.toString() : null;
        }
        return null;
    }

    private String resolveIdToken(OAuth2Authorization authorization) {
        final OAuth2Authorization.Token<OidcIdToken> idToken = authorization.getToken(OidcIdToken.class);
        return idToken != null ? idToken.getToken().getTokenValue() : null;
    }
}
