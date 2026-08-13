package co.pitayagroup.mcp.broadworks.auth.session;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;
import co.pitayagroup.mcp.broadworks.config.PublicBaseUrlProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.BadOpaqueTokenException;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;

/**
 * Resolves opaque bearer access tokens <b>locally</b> against the {@link SessionStore} (no network
 * introspection). Validates expiry and RFC 8707 audience (canonical MCP resource).
 */
@Slf4j
@RequiredArgsConstructor
public class StoreOpaqueTokenIntrospector implements OpaqueTokenIntrospector {

    private final SessionStore sessionStore;
    private final PublicBaseUrlProperties publicBaseUrl;

    @Override
    public OAuth2AuthenticatedPrincipal introspect(String token) {
        final Session session = sessionStore.getSessionByAccessToken(token)
                .orElseThrow(() -> {
                    log.warn("Bearer token introspection failed: token is not active (no matching session)");
                    return new BadOpaqueTokenException("Provided token is not active");
                });

        final String canonical = publicBaseUrl.mcpResourceUrl();
        if (session.audience() == null || session.audience().isBlank()
                || !PublicBaseUrlProperties.resourceMatches(session.audience(), canonical)) {
            log.warn("Bearer token introspection failed: audience mismatch for subject={}. Expected={}, Found={}",
                    session.subject(), canonical, session.audience());
            throw new BadOpaqueTokenException("Provided token is not authorized for this resource");
        }

        final Instant expiresAt = session.accessTokenExpiresAt();
        if (expiresAt != null && Instant.now().isAfter(expiresAt)) {
            log.warn("Bearer token introspection failed: token expired at {} for subject={}",
                    expiresAt, session.subject());
            throw new BadOpaqueTokenException("Provided token has expired");
        }

        final Map<String, Object> attributes = new HashMap<>();
        attributes.put(UserInfo.SUBJECT_ATTRIBUTE, session.subject());
        if (session.email() != null) {
            attributes.put(UserInfo.EMAIL_ATTRIBUTE, session.email());
        }
        attributes.put("active", true);
        attributes.put("aud", session.audience());

        final List<org.springframework.security.core.GrantedAuthority> authorities =
                AuthorityUtils.NO_AUTHORITIES;
        log.debug("Bearer token introspection succeeded for subject={} (email present={})",
                session.subject(), session.email() != null);
        return new DefaultOAuth2AuthenticatedPrincipal(session.subject(), attributes, authorities);
    }
}
