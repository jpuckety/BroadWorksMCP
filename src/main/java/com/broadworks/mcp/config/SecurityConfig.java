package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.oauth.BearerChallengeEntryPoint;
import com.broadworks.mcp.auth.session.StoreOpaqueTokenIntrospector;
import com.broadworks.mcp.auth.store.SessionStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.server.resource.introspection.OpaqueTokenIntrospector;
import org.springframework.security.web.SecurityFilterChain;

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
                .oauth2Login(Customizer.withDefaults())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(bearerChallengeEntryPoint))
                // Bearer APIs are stateless; disable CSRF for them (login flow is redirect-based).
                .csrf(csrf -> csrf.disable());
        return http.build();
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
