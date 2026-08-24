package co.pitayagroup.mcp.broadworks.mcp.tools;

import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelToolsTest {

    private final DomainModelTools tools = new DomainModelTools();

    @Test
    void returnsConciseMarkdownReference() {
        final String body = tools.getDomainModel();

        assertThat(body)
                .contains("System → Service Provider / Enterprise → Group → User")
                .contains("Access Device ↔ User is many-to-many")
                .contains("Prefer assigning a Service Pack")
                .contains("Authorization flows downward")
                .contains("Authorized ≠ Assigned")
                .contains("serviceProviderId")
                .contains("groupId")
                .contains("userId")
                .contains("resourceId")
                .contains("Read-only");
        assertThat(body.length()).isLessThan(3500);
    }

    @Test
    void registersReadOnlyStyleToolWithNoRequiredParameters() {
        final MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build();

        assertThat(provider.getToolCallbacks()).hasSize(1);
        final ToolDefinition definition = provider.getToolCallbacks()[0].getToolDefinition();
        assertThat(definition.name()).isEqualTo("broadworks_get_domain_model");
        assertThat(definition.description()).isEqualTo(DomainModelTools.DESCRIPTION);
        assertThat(definition.inputSchema())
                .contains("\"type\"")
                .doesNotContain("\"required\":[");
    }

    @Test
    void callbackReturnsTheReference() {
        final ToolCallback callback = MethodToolCallbackProvider.builder()
                .toolObjects(tools)
                .build()
                .getToolCallbacks()[0];

        // MethodToolCallback serializes the String return value as a JSON string.
        assertThat(callback.call("{}"))
                .contains("BroadWorks object model")
                .contains("many-to-many")
                .contains("Authorized")
                .contains("serviceProviderId");
    }
}
