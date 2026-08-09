package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.session.OpaqueTokenFactory;
import com.broadworks.mcp.auth.session.StoreBackedAuthorizationService;
import com.broadworks.mcp.auth.session.StoreBackedRegisteredClientRepository;
import com.broadworks.mcp.auth.store.SessionStore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * Spring Authorization Server configuration.
 *
 * <p>Uses SAS <b>default</b> endpoints ({@code /oauth2/authorize}, {@code /oauth2/token},
 * {@code /oauth2/jwks}, discovery at {@code /.well-known/oauth-authorization-server}) per the updated
 * Step 4 decision. Clients and issued sessions are backed by the pluggable {@link SessionStore};
 * access tokens are opaque (REFERENCE). Unauthenticated browser hits on the authorize endpoint are
 * redirected to Google login.</p>
 */
@Configuration(proxyBeanMethods = false)
public class AuthorizationServerConfig {

    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        final OAuth2AuthorizationServerConfigurer authorizationServer =
                OAuth2AuthorizationServerConfigurer.authorizationServer();
        http
                .securityMatcher(authorizationServer.getEndpointsMatcher())
                .with(authorizationServer, server -> server.oidc(Customizer.withDefaults()))
                .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
                // For browser clients hitting the authorize endpoint unauthenticated, start Google login.
                .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                        new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google"),
                        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));
        return http.build();
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
    public OAuth2AuthorizationService authorizationService(SessionStore sessionStore) {
        return new StoreBackedAuthorizationService(sessionStore);
    }

    @Bean
    @ConditionalOnMissingBean(AuthorizationServerSettings.class)
    public AuthorizationServerSettings authorizationServerSettings() {
        // SAS default endpoints (Google/SAS defaults per updated Step 4 decision).
        return AuthorizationServerSettings.builder().build();
    }
}
