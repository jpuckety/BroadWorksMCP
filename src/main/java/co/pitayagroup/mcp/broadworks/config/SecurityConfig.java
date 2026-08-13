package co.pitayagroup.mcp.broadworks.config;

import co.pitayagroup.mcp.broadworks.auth.oauth.BearerChallengeEntryPoint;
import co.pitayagroup.mcp.broadworks.auth.session.StoreOpaqueTokenIntrospector;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.LinkedHashSet;
import java.util.List;
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

    /**
     * Paths exempt from CSRF: the bearer-token-only MCP transports and the RFC 7591 registration
     * endpoint, all called by non-browser clients that hold no session and cannot carry a CSRF token.
     * Everything else on this chain (notably the {@code oauth2Login} flow and the Spring-Session-backed
     * browser paths) keeps CSRF protection. The Authorization Server endpoints ({@code /oauth2/token},
     * {@code /oauth2/authorize}, ...) live on their own filter chain, where
     * {@code OAuth2AuthorizationServerConfigurer} already ignores CSRF for them.
     */
    private static final String[] CSRF_EXEMPT_PATHS = {
            "/mcp",
            "/mcp/**",
            "/sse",
            "/oauth/register"
    };

    /**
     * Endpoints a browser-hosted MCP client calls cross-origin: the transports, the discovery
     * documents, Dynamic Client Registration and the Authorization Server endpoints (the token
     * exchange in particular).
     */
    private static final List<String> CORS_PATHS = List.of(
            "/mcp", "/mcp/**", "/sse", "/sse/**",
            "/.well-known/**", "/oauth/register", "/oauth2/**");

    @Bean
    @Order(2)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain appSecurityFilterChain(HttpSecurity http,
                                                      OpaqueTokenIntrospector opaqueTokenIntrospector,
                                                      BearerChallengeEntryPoint bearerChallengeEntryPoint,
                                                      PublicBaseUrlProperties publicBaseUrl,
                                                      CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        final String baseUrl = publicBaseUrl.baseUrl();
        http
                .securityMatcher("/**")
                // Preflight requests carry no credentials, so CORS must be handled ahead of
                // authorization; unlisted origins simply get no Access-Control-Allow-Origin.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
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
                // Bearer APIs are stateless and cannot carry a CSRF token; the session-backed browser
                // paths (oauth2Login, /oauth2/authorize) stay CSRF protected.
                .csrf(csrf -> csrf.ignoringRequestMatchers(CSRF_EXEMPT_PATHS));
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

    /**
     * CORS rules for the MCP + OAuth endpoints (see {@link CorsProperties}). Cookies are never
     * allowed: MCP clients authenticate with a bearer token, and {@code WWW-Authenticate} is exposed
     * so a browser client can read the {@code resource_metadata} URL from the 401 challenge.
     */
    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public CorsConfigurationSource corsConfigurationSource(CorsProperties corsProperties) {
        final UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        if (!corsProperties.isEnabled()) {
            return source;
        }
        final CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(corsProperties.effectiveAllowedOrigins());
        configuration.setAllowedMethods(List.of("GET", "POST", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of(
                HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE, HttpHeaders.ACCEPT,
                "Mcp-Session-Id", "Mcp-Protocol-Version", "Last-Event-ID"));
        configuration.setExposedHeaders(List.of(HttpHeaders.WWW_AUTHENTICATE, "Mcp-Session-Id"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(1800L);
        CORS_PATHS.forEach(path -> source.registerCorsConfiguration(path, configuration));
        return source;
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
