package co.pitayagroup.mcp.broadworks.config;

import java.util.function.Consumer;

import co.pitayagroup.mcp.broadworks.auth.oauth.LoopbackAwareRedirectUriValidator;
import co.pitayagroup.mcp.broadworks.auth.session.OpaqueTokenFactory;
import co.pitayagroup.mcp.broadworks.auth.session.StoreBackedAuthorizationService;
import co.pitayagroup.mcp.broadworks.auth.session.StoreBackedRegisteredClientRepository;
import co.pitayagroup.mcp.broadworks.auth.store.AuthorizationStore;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationContext;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationException;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationProvider;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationValidator;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * Spring Authorization Server configuration.
 *
 * <p>Uses SAS default endpoints ({@code /oauth2/authorize}, {@code /oauth2/token},
 * {@code /oauth2/jwks}, discovery at {@code /.well-known/oauth-authorization-server}). Authorizations
 * are durable via {@link AuthorizationStore}; issued sessions via {@link SessionStore}.
 * Unauthenticated browser hits on the authorize endpoint redirect to Google login.</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    /** RFC 7591 Dynamic Client Registration path served by the custom controller. */
    private static final String REGISTRATION_ENDPOINT_PATH = "/oauth/register";

    /** RFC 8707 error code for invalid resource indicator. */
    private static final String INVALID_TARGET = "invalid_target";

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http,
                                                                      PublicBaseUrlProperties publicBaseUrl,
                                                                      CorsConfigurationSource corsConfigurationSource)
            throws Exception {
        final OAuth2AuthorizationServerConfigurer authorizationServer =
                new OAuth2AuthorizationServerConfigurer();
        final String registrationEndpoint = publicBaseUrl.baseUrl() + REGISTRATION_ENDPOINT_PATH;
        final String canonicalResource = publicBaseUrl.mcpResourceUrl();
        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                // Browser-hosted clients exchange the authorization code from their own origin, so the
                // token endpoint needs the same CORS handling (and preflight pass-through) as /mcp.
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .with(authorizationServer, server -> server
                        .oidc(Customizer.withDefaults())
                        .authorizationEndpoint(endpoint -> endpoint
                                .authenticationProviders(providers ->
                                        configureResourceValidators(providers, canonicalResource)))
                        .authorizationServerMetadataEndpoint(metadata -> metadata
                                .authorizationServerMetadataCustomizer(builder -> builder
                                        .clientRegistrationEndpoint(registrationEndpoint)
                                        .tokenEndpointAuthenticationMethod("none"))))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
    }

    /**
     * Reject authorize requests whose RFC 8707 {@code resource} does not match the canonical MCP
     * resource. Loopback {@code redirect_uri}s (including {@code localhost}) allow any port per
     * RFC 8252; other redirect / scope checks stay on the SAS defaults.
     */
    private static void configureResourceValidators(java.util.List<AuthenticationProvider> providers,
                                                    String canonicalResource) {
        for (AuthenticationProvider provider : providers) {
            if (provider instanceof OAuth2AuthorizationCodeRequestAuthenticationProvider codeProvider) {
                final Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> validator =
                        LoopbackAwareRedirectUriValidator.INSTANCE
                                .andThen(OAuth2AuthorizationCodeRequestAuthenticationValidator.DEFAULT_SCOPE_VALIDATOR)
                                .andThen(resourceValidator(canonicalResource));
                codeProvider.setAuthenticationValidator(loggingValidator(validator));
            }
        }
    }

    private static Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> loggingValidator(
            Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> validator) {
        return context -> {
            try {
                validator.accept(context);
            } catch (OAuth2AuthorizationCodeRequestAuthenticationException ex) {
                final OAuth2AuthorizationCodeRequestAuthenticationToken authentication =
                        context.getAuthentication();
                log.warn("Authorization request rejected clientId={} redirectUri={} error={} description={}",
                        authentication.getClientId(),
                        authentication.getRedirectUri(),
                        ex.getError().getErrorCode(),
                        ex.getError().getDescription());
                throw ex;
            }
        };
    }

    private static Consumer<OAuth2AuthorizationCodeRequestAuthenticationContext> resourceValidator(
            String canonicalResource) {
        return context -> {
            final OAuth2AuthorizationCodeRequestAuthenticationToken authentication =
                    context.getAuthentication();
            final Object resource = authentication.getAdditionalParameters()
                    .get(StoreBackedAuthorizationService.RESOURCE_PARAMETER);
            if (resource == null || !StringUtils.hasText(resource.toString())) {
                return;
            }
            if (!PublicBaseUrlProperties.resourceMatches(resource.toString(), canonicalResource)) {
                final OAuth2Error error = new OAuth2Error(
                        INVALID_TARGET,
                        "The requested resource does not match this authorization server's MCP resource",
                        null);
                throw new OAuth2AuthorizationCodeRequestAuthenticationException(error, authentication);
            }
        };
    }

    @Bean
    public OpaqueTokenFactory opaqueTokenFactory() {
        return new OpaqueTokenFactory();
    }

    @Bean
    @ConditionalOnMissingBean(RegisteredClientRepository.class)
    public RegisteredClientRepository registeredClientRepository(SessionStore sessionStore,
                                                                 AuthTokenProperties tokenProperties) {
        return new StoreBackedRegisteredClientRepository(sessionStore, tokenProperties);
    }

    @Bean
    @ConditionalOnMissingBean(OAuth2AuthorizationService.class)
    public OAuth2AuthorizationService authorizationService(AuthorizationStore authorizationStore,
                                                           SessionStore sessionStore,
                                                           PublicBaseUrlProperties publicBaseUrl) {
        return new StoreBackedAuthorizationService(authorizationStore, sessionStore, publicBaseUrl);
    }

    @Bean
    @ConditionalOnMissingBean(AuthorizationServerSettings.class)
    public AuthorizationServerSettings authorizationServerSettings(PublicBaseUrlProperties publicBaseUrl) {
        return AuthorizationServerSettings.builder()
                .issuer(publicBaseUrl.baseUrl())
                .build();
    }
}
