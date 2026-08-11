package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.oauth.BearerChallengeEntryPoint;
import com.broadworks.mcp.auth.session.StoreOpaqueTokenIntrospector;
import com.broadworks.mcp.auth.store.SessionStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Resource-Server + interactive-login security for everything outside the Authorization Server
 * endpoints.
 *
 * <ul>
 *   <li>MCP and other protected endpoints require a valid opaque bearer token, introspected locally
 *       against the {@link SessionStore}; failures yield a 401 {@code WWW-Authenticate} challenge.</li>
 *   <li>Discovery / metadata, dynamic client registration, health, and the Google login entry points
 *       are public.</li>
 *   <li>Interactive Google sign-in is available via {@code oauth2Login} (default redirection endpoint
 *       {@code /login/oauth2/code/google}).</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/.well-known/**",
            "/oauth/register",
            "/actuator/health/**",
            "/actuator/info",
            "/error",
            "/login/**",
            "/oauth2/authorization/**",
            "/login/oauth2/code/**"
    };

    @Bean
    @Order(2)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
                                                      OpaqueTokenIntrospector opaqueTokenIntrospector,
                                                      BearerChallengeEntryPoint bearerChallengeEntryPoint,
                                                      PublicBaseUrlProperties publicBaseUrl)
            throws Exception {
        final String baseUrl = publicBaseUrl.baseUrl();
        http
                .securityMatcher("/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .anyRequest().authenticated())
                .oauth2ResourceServer(resourceServer -> resourceServer
                        .authenticationEntryPoint(bearerChallengeEntryPoint)
                        .opaqueToken(opaque -> opaque.introspector(opaqueTokenIntrospector))
                        // Spring Security 7 publishes RFC 9728 protected-resource metadata at
                        // /.well-known/oauth-protected-resource itself. Customize it to advertise this
                        // server as the resource and point clients at the authorization server (issuer),
                        // pinned to the external base URL. Replaces the previous custom controller.
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(baseUrl)
                                        .authorizationServer(baseUrl)
                                        .scope("openid")
                                        .scope("email")
                                        .scope("profile"))))
                // Interactive Google sign-in. A custom user-authorities mapper appends a
                // FactorGrantedAuthority (FACTOR_AUTHORIZATION_CODE) to the authenticated principal.
                //
                // Why this is required: Google login uses OIDC, so Spring Security's
                // OidcAuthorizationCodeAuthenticationProvider produces the principal. Unlike the plain
                // OAuth2LoginAuthenticationProvider (which stamps FACTOR_AUTHORIZATION_CODE itself), the
                // OIDC provider does NOT add any FactorGrantedAuthority. When this login principal is later
                // reused by the Authorization Server at POST /oauth/token to mint the downstream OIDC
                // ID token, Spring Security 7's JwtGenerator derives the ID token's auth_time claim solely
                // from the newest FactorGrantedAuthority#issuedAt on the principal. With none present it
                // throws "IllegalArgumentException: authenticationTime cannot be null", failing token
                // issuance with a 500. Adding the factor here (issuedAt defaults to login time) restores it.
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userAuthoritiesMapper(factorStampingAuthoritiesMapper())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(bearerChallengeEntryPoint))
                // Bearer APIs are stateless; disable CSRF for them (login flow is redirect-based).
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * {@link GrantedAuthoritiesMapper} that preserves the mapped OIDC/OAuth2 authorities and adds a
     * {@link FactorGrantedAuthority} for the authorization-code factor. This is wired into both the
     * OIDC and non-OIDC login providers by {@code oauth2Login}, ensuring the authenticated principal
     * always carries the authentication-factor marker that Spring Security 7's {@code JwtGenerator}
     * needs to compute the ID token {@code auth_time} claim.
     */
    static GrantedAuthoritiesMapper factorStampingAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
            mapped.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY));
            return mapped;
        };
    }

    @Bean
    public BearerChallengeEntryPoint bearerChallengeEntryPoint(PublicBaseUrlProperties publicBaseUrl) {
        return new BearerChallengeEntryPoint(publicBaseUrl);
    }

    @Bean
    @ConditionalOnMissingBean(OpaqueTokenIntrospector.class)
    public OpaqueTokenIntrospector opaqueTokenIntrospector(SessionStore sessionStore) {
        return new StoreOpaqueTokenIntrospector(sessionStore);
    }

    /**
     * Google {@link ClientRegistration} derived from {@link OidcProperties}. Placeholder credentials
     * are used when Google is not configured (local/tests) so the context still starts; the login
     * flow is only exercised when a real client id is provided.
     */
    @Bean
    @ConditionalOnMissingBean(ClientRegistrationRepository.class)
    public ClientRegistrationRepository clientRegistrationRepository(OidcProperties oidcProperties) {
        final String clientId = (oidcProperties.clientId() == null || oidcProperties.clientId().isBlank())
                ? "unconfigured-google-client"
                : oidcProperties.clientId();
        final String clientSecret = oidcProperties.clientSecret() == null ? "" : oidcProperties.clientSecret();
        final ClientRegistration google = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .build();
        return new InMemoryClientRegistrationRepository(google);
    }
}
