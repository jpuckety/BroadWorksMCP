package com.broadworks.mcp.auth.session;

import java.security.Principal;
import java.time.Instant;

import com.broadworks.mcp.auth.store.Session;
import com.broadworks.mcp.auth.store.SessionStore;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

/**
 * {@link OAuth2AuthorizationService} that keeps SAS's transient authorization state (authorization
 * codes, pending authorizations) <b>process-local</b> via an in-memory delegate, while persisting a
 * durable {@link Session} to the {@link SessionStore} whenever an access token is issued.
 *
 * <p>The persisted session is what the Resource Server introspects on every bearer request, giving
 * durable, cross-restart token validation without serialising the full SAS authorization graph. The
 * session id equals the opaque access-token value so token rotation maps 1:1 to a session row.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class StoreBackedAuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate = new InMemoryOAuth2AuthorizationService();
    private final SessionStore sessionStore;

    @Override
    public void save(OAuth2Authorization authorization) {
        delegate.save(authorization);
        syncSession(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        delegate.remove(authorization);
        final OAuth2Authorization.Token<OAuth2AccessToken> accessToken = authorization.getAccessToken();
        if (accessToken != null) {
            sessionStore.deleteSession(accessToken.getToken().getTokenValue());
        }
    }

    @Override
    public OAuth2Authorization findById(String id) {
        return delegate.findById(id);
    }

    @Override
    public OAuth2Authorization findByToken(String token, OAuth2TokenType tokenType) {
        return delegate.findByToken(token, tokenType);
    }

    private void syncSession(OAuth2Authorization authorization) {
        final OAuth2Authorization.Token<OAuth2AccessToken> accessTokenHolder = authorization.getAccessToken();
        if (accessTokenHolder == null) {
            return; // No access token yet (e.g. authorization-code step) — stays process-local.
        }
        final OAuth2AccessToken accessToken = accessTokenHolder.getToken();
        final OAuth2Authorization.Token<OAuth2RefreshToken> refreshHolder = authorization.getRefreshToken();
        final String refreshToken = refreshHolder != null ? refreshHolder.getToken().getTokenValue() : null;
        final Instant refreshExpiresAt = refreshHolder != null ? refreshHolder.getToken().getExpiresAt() : null;

        final String subject = authorization.getPrincipalName();
        final String email = resolveEmail(authorization);
        final String idToken = resolveIdToken(authorization);

        final Session session = new Session(
                accessToken.getTokenValue(),   // sessionId == access token value
                accessToken.getTokenValue(),
                refreshToken,
                authorization.getRegisteredClientId(),
                subject,
                email,
                idToken,
                null,
                accessToken.getExpiresAt(),
                refreshExpiresAt,
                Instant.now());
        sessionStore.createSession(session);
        log.debug("Persisted session for subject={} client={}", subject, authorization.getRegisteredClientId());
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
        if (authentication != null && authentication.getPrincipal()
                instanceof org.springframework.security.oauth2.core.user.OAuth2User user) {
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
