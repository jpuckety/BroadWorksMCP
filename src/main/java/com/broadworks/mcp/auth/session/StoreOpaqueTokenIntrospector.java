package com.broadworks.mcp.auth.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.broadworks.mcp.auth.store.Session;
import com.broadworks.mcp.auth.store.SessionStore;

import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Resolves opaque bearer access tokens <b>locally</b> against the {@link SessionStore} (no network
 * introspection). Since the Authorization Server and Resource Server share a process/datastore, a
 * valid token maps to a persisted {@link Session}; the resulting principal carries {@code sub} and
 * {@code email} attributes consumed by {@link UserContext}.
 */
@RequiredArgsConstructor
public class StoreOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    private final SessionStore sessionStore;

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        final Session session = sessionStore.getSessionByAccessToken(token)
                .orElseThrow(() -> new BadOpaqueTokenException("Provided token is not active"));

        final Instant expiresAt = session.accessTokenExpiresAt();
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            throw new BadOpaqueTokenException("Provided token has expired");
        }

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(UserInfo.SUBJECT_ATTRIBUTE, session.subject());
        if (session.email() != null) {
            attributes.put(UserInfo.EMAIL_ATTRIBUTE, session.email());
        }
        attributes.put("active", true);

        final List<org.springframework.security.core.GrantedAuthority> authorities =
                AuthorityUtils.NO_AUTHORITIES;
        return new DefaultOAuth2AuthenticatedPrincipal(session.subject(), attributes, authorities);
    }
}
