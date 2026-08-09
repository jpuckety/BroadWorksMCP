package com.broadworks.mcp.config;

import java.net.http.HttpClient;
import java.time.Duration;

import com.broadworks.mcp.auth.identity.GoogleIdentityProvider;
import com.broadworks.mcp.auth.identity.IdentityProvider;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the pluggable {@link IdentityProvider}. The default is {@link GoogleIdentityProvider};
 * tests can supply their own bean (e.g. a stub), which this configuration backs off from.
 */
@Configuration(proxyBeanMethods = false)
public class IdentityConfig {

    /** Connect timeout for upstream IdP calls. */
    private static final Duration IDP_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    @Bean
    @ConditionalOnMissingBean(HttpClient.class)
    public HttpClient identityHttpClient() {
        return HttpClient.newBuilder().connectTimeout(IDP_CONNECT_TIMEOUT).build();
    }

    @Bean
    @ConditionalOnMissingBean(IdentityProvider.class)
    public IdentityProvider identityProvider(OidcProperties oidcProperties,
                                             PublicBaseUrlProperties publicBaseUrlProperties,
                                             HttpClient identityHttpClient,
                                             ObjectMapper objectMapper) {
        return new GoogleIdentityProvider(oidcProperties, publicBaseUrlProperties,
                identityHttpClient, objectMapper);
    }
}
