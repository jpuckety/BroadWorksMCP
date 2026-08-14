package co.pitayagroup.mcp.broadworks.mcp;

import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.GroupTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceProviderTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.UserTools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the BroadWorks MCP tool catalogue as a spec-compliant MCP {@code tools/list} JSON-RPC
 * response, entirely offline.
 *
 * <p>It reuses the very same {@link MethodToolCallbackProvider} wiring the application registers in
 * {@code McpToolConfig} (see {@code ToolRegistrationProbeTest}), so the emitted list is authoritative
 * and always in sync with the {@code @Tool}-annotated methods. Because only the tool <em>definitions</em>
 * (name, description, JSON input schema) are needed, the tool beans are constructed with a {@code null}
 * connection factory: no BroadWorks connection, no authentication, and no running server are involved.
 * This makes it safe to invoke right after a deployment to show which tools the freshly deployed server
 * exposes.</p>
 *
 * <p>The result is the {@code result} of a JSON-RPC {@code tools/list} response:
 * {@code {"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}}. When a server URL is supplied it is
 * surfaced under the optional {@code result._meta.serverUrl} field so the response can be tied back to
 * the concrete deployed endpoint without breaking JSON-RPC/MCP compliance.</p>
 */
public final class McpToolsListDumper {

    /**
     * Command-line flag on the application entry point that renders the {@code tools/list} response and
     * exits instead of booting the Spring context. An optional server URL may follow it.
     */
    public static final String DUMP_TOOLS_FLAG = "--dump-tools";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolsListDumper() {
    }

    /**
     * Builds the MCP {@code tools/list} JSON-RPC response as a pretty-printed JSON string.
     *
     * @param serverUrl the deployed MCP endpoint URL to advertise under {@code result._meta.serverUrl},
     *                  or {@code null}/blank to omit it.
     * @return the pretty-printed JSON-RPC {@code tools/list} response.
     */
    public static String toolsListJson(String serverUrl) {
        final MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new ConnectionTools(null), new ServiceProviderTools(null),
                        new GroupTools(null), new UserTools(null))
                .build();

        final ArrayNode tools = MAPPER.createArrayNode();
        for (ToolCallback callback : provider.getToolCallbacks()) {
            final ToolDefinition definition = callback.getToolDefinition();
            final ObjectNode tool = MAPPER.createObjectNode();
            tool.put("name", definition.name());
            tool.put("description", definition.description());
            final String schema = definition.inputSchema();
            tool.set("inputSchema",
                    schema == null || schema.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(schema));
            tools.add(tool);
        }

        final ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        if (serverUrl != null && !serverUrl.isBlank()) {
            final ObjectNode meta = MAPPER.createObjectNode();
            meta.put("serverUrl", serverUrl.trim());
            result.set("_meta", meta);
        }

        final ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", 1);
        response.set("result", result);

        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(response);
    }

    /**
     * Prints the {@code tools/list} response to standard out. An optional first argument is the deployed
     * server URL to advertise.
     */
    public static void main(String[] args) {
        final String serverUrl = args != null && args.length > 0 ? args[0] : null;
        System.out.println(toolsListJson(serverUrl));
    }
}
