package com.broadworks.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;

/**
 * Verifies the authorities mapper wired into {@code oauth2Login}. Without a
 * {@link FactorGrantedAuthority} on the authenticated principal, Spring Security 7's
 * {@code JwtGenerator} throws "authenticationTime cannot be null" when minting the OIDC ID token at
 * the token endpoint (the OIDC login provider does not add one on its own). The mapper must add the
 * authorization-code factor while preserving all originally mapped authorities.
 */
class SecurityConfigTest {

    @Test
    void mapperAddsAuthorizationCodeFactorWhilePreservingOriginalAuthorities() {
        final Instant before = Instant.now();
        final GrantedAuthoritiesMapper mapper = SecurityConfig.factorStampingAuthoritiesMapper();

        final SimpleGrantedAuthority oidcUser = new SimpleGrantedAuthority("OIDC_USER");
        final SimpleGrantedAuthority scopeOpenid = new SimpleGrantedAuthority("SCOPE_openid");

        final Collection<? extends GrantedAuthority> mapped =
                mapper.mapAuthorities(List.of(oidcUser, scopeOpenid));

        // Original authorities are preserved.
        assertThat(mapped).extracting(GrantedAuthority::getAuthority)
                .contains("OIDC_USER", "SCOPE_openid");

        // Exactly one FactorGrantedAuthority for the authorization-code factor is added, with a sane issuedAt.
        final List<FactorGrantedAuthority> factors = mapped.stream()
                .filter(FactorGrantedAuthority.class::isInstance)
                .map(FactorGrantedAuthority.class::cast)
                .toList();
        assertThat(factors).hasSize(1);
        assertThat(factors.get(0).getAuthority())
                .isEqualTo(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY);
        assertThat(factors.get(0).getIssuedAt())
                .isBetween(before, Instant.now());
    }
}
