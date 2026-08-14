package co.pitayagroup.mcp.broadworks.config;

import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.GroupTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceProviderTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.UserTools;

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
    public ToolCallbackProvider broadWorksToolCallbackProvider(ConnectionTools connectionTools,
                                                               ServiceProviderTools serviceProviderTools,
                                                               GroupTools groupTools,
                                                               UserTools userTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(connectionTools, serviceProviderTools, groupTools, userTools)
                .build();
    }
}
