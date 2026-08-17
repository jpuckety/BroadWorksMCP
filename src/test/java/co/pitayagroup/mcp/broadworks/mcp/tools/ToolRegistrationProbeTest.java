package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast, context-free guard that the same {@link MethodToolCallbackProvider} the application wires in
 * {@code McpToolConfig} discovers every BroadWorks {@code @Tool} method across all tool objects.
 */
class ToolRegistrationProbeTest {

    @Test
    void allBroadWorksToolsAreDiscovered() {
        MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new ConnectionTools(null), new ServiceProviderTools(null), new GroupTools(null),
                        new UserTools(null))
                .build();

        String[] names = Arrays.stream(provider.getToolCallbacks())
                .map(ToolCallback::getToolDefinition)
                .map(def -> def.name())
                .toArray(String[]::new);

        assertThat(names).containsExactlyInAnyOrder(
                "broadworks_add_connection",
                "broadworks_list_connections",
                "broadworks_delete_connection",
                "broadworks_list_service_providers",
                "broadworks_get_service_provider",
                "broadworks_modify_service_provider",
                "broadworks_list_groups",
                "broadworks_get_group",
                "broadworks_list_users",
                "broadworks_get_user");
    }
}
