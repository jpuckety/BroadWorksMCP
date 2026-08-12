package com.broadworks.mcp.web;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the access-logging filters: {@link McpEndpointLoggingFilter} against the MCP transport
 * URLs ({@code /mcp} and the legacy {@code /sse}) and {@link OAuthEndpointLoggingFilter} against the
 * OAuth / discovery surface, so a client that fails during discovery, registration, or the
 * authorization-code flow is traceable instead of silent. Health traffic is deliberately not logged.
 *
 * <p>Both filters are ordered just ahead of the Spring Security filter chain (whose default order is
 * {@code -100}) so that the correlation-id {@link org.slf4j.MDC} they stamp is already in place for
 * the security, token-introspection, and bearer-challenge log lines produced while authenticating
 * the request.</p>
 */
@Configuration(proxyBeanMethods = false)
public class McpLoggingConfig {

    /** One less than Spring Security's default filter order ({@code -100}); runs just before it. */
    private static final int MCP_LOGGING_FILTER_ORDER = -101;

    /**
     * OAuth / discovery endpoints access-logged by {@link OAuthEndpointLoggingFilter}: RFC 9728 and
     * RFC 8414 metadata, Dynamic Client Registration, the Authorization Server endpoints, and the
     * Google login hand-off (including its callback).
     */
    private static final List<String> OAUTH_URL_PATTERNS = List.of(
            "/.well-known/*", "/oauth/*", "/oauth2/*", "/login/*");

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<McpEndpointLoggingFilter> mcpEndpointLoggingFilter(ObjectMapper objectMapper) {
        final FilterRegistrationBean<McpEndpointLoggingFilter> registration =
                new FilterRegistrationBean<>(new McpEndpointLoggingFilter(objectMapper));
        registration.setUrlPatterns(List.of("/mcp", "/mcp/*", "/sse", "/sse/*"));
        registration.setOrder(MCP_LOGGING_FILTER_ORDER);
        registration.setName("mcpEndpointLoggingFilter");
        return registration;
    }

    @Bean
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public FilterRegistrationBean<OAuthEndpointLoggingFilter> oauthEndpointLoggingFilter() {
        final FilterRegistrationBean<OAuthEndpointLoggingFilter> registration =
                new FilterRegistrationBean<>(new OAuthEndpointLoggingFilter());
        registration.setUrlPatterns(OAUTH_URL_PATTERNS);
        registration.setOrder(MCP_LOGGING_FILTER_ORDER);
        registration.setName("oauthEndpointLoggingFilter");
        return registration;
    }
}
