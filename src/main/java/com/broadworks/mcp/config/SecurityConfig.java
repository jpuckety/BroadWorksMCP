package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.oauth.BearerChallengeEntryPoint;
import com.broadworks.mcp.auth.session.StoreOpaqueTokenIntrospector;
import com.broadworks.mcp.auth.store.SessionStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.mapping.GrantedAuthoritiesMapper;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
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
 *       {@code /login/oauth2/code/google}). Unverified Google emails are rejected.</li>
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
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
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
                        .protectedResourceMetadata(metadata -> metadata
                                .protectedResourceMetadataCustomizer(builder -> builder
                                        .resource(baseUrl + "/mcp")
                                        .authorizationServer(baseUrl)
                                        .scope("openid")
                                        .scope("email")
                                        .scope("profile"))))
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userAuthoritiesMapper(factorStampingAuthoritiesMapper())
                                .oidcUserService(oidcUserService())))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(bearerChallengeEntryPoint))
                // Bearer APIs are stateless; disable CSRF (login + consent are form/redirect based).
                .csrf(csrf -> csrf.disable());
        return http.build();
    }

    /**
     * {@link GrantedAuthoritiesMapper} that preserves the mapped OIDC/OAuth2 authorities and adds a
     * {@link FactorGrantedAuthority} for the authorization-code factor (needed for SAS ID token
     * {@code auth_time}).
     */
    static GrantedAuthoritiesMapper factorStampingAuthoritiesMapper() {
        return authorities -> {
            Set<GrantedAuthority> mapped = new LinkedHashSet<>(authorities);
            mapped.add(FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.AUTHORIZATION_CODE_AUTHORITY));
            return mapped;
        };
    }

    /**
     * Rejects Google logins when {@code email_verified} is not {@code true}.
     */
    private OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        final OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            final OidcUser oidcUser = delegate.loadUser(userRequest);
            if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("email_not_verified"),
                        "Identity verification failed: your Google email address is not verified.");
            }
            return oidcUser;
        };
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public BearerChallengeEntryPoint bearerChallengeEntryPoint(PublicBaseUrlProperties publicBaseUrl) {
        return new BearerChallengeEntryPoint(publicBaseUrl);
    }

    @Bean
    @ConditionalOnMissingBean(OpaqueTokenIntrospector.class)
    public OpaqueTokenIntrospector opaqueTokenIntrospector(SessionStore sessionStore,
                                                           PublicBaseUrlProperties publicBaseUrl) {
        return new StoreOpaqueTokenIntrospector(sessionStore, publicBaseUrl);
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
