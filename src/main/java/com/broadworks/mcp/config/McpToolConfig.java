package com.broadworks.mcp.config;

import com.broadworks.mcp.mcp.tools.GroupTools;
import com.broadworks.mcp.mcp.tools.ServiceProviderTools;

import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the BroadWorks MCP tools with the Spring AI MCP server.
 *
 * <p>All {@code @Tool}-annotated methods on the supplied tool beans are exposed automatically. Adding
 * a new tool set (e.g. Users, Devices, Call Centers, CDRs) is as simple as writing a new
 * {@code @Tool} bean and listing it here (or making this discover all such beans).</p>
 */
@Configuration(proxyBeanMethods = false)
public class McpToolConfig {

    @Bean
    public ToolCallbackProvider broadWorksToolCallbackProvider(ServiceProviderTools serviceProviderTools,
                                                               GroupTools groupTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(serviceProviderTools, groupTools)
                .build();
    }
}
