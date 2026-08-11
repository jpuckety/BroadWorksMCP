package com.broadworks.mcp.web;

import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import tools.jackson.databind.ObjectMapper;

/**
 * Registers the {@link McpEndpointLoggingFilter} against the MCP transport URLs only ({@code /mcp}
 * and the legacy {@code /sse}), so ordinary OAuth / discovery / health traffic is not access-logged
 * twice.
 *
 * <p>The filter is ordered just ahead of the Spring Security filter chain (whose default order is
 * {@code -100}) so that the correlation-id {@link org.slf4j.MDC} it stamps is already in place for
 * the security, token-introspection, and bearer-challenge log lines produced while authenticating
 * the request.</p>
 */
@Configuration(proxyBeanMethods = false)
public class McpLoggingConfig {

    /** One less than Spring Security's default filter order ({@code -100}); runs just before it. */
    private static final int MCP_LOGGING_FILTER_ORDER = -101;

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
}
