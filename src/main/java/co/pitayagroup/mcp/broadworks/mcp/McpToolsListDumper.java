package co.pitayagroup.mcp.broadworks.mcp;

import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.GroupTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServicePackTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceProviderTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.UserTools;

import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the BroadWorks MCP capability catalogue as spec-compliant MCP {@code tools/list},
 * {@code resources/list} and {@code prompts/list} JSON-RPC responses, entirely offline.
 *
 * <p>It reuses the very same {@link MethodToolCallbackProvider} wiring the application registers in
 * {@code McpToolConfig} (see {@code ToolRegistrationProbeTest}), so the emitted list is authoritative
 * and always in sync with the {@code @Tool}-annotated methods. Because only the tool <em>definitions</em>
 * (name, description, JSON input schema) are needed, the tool beans are constructed with a {@code null}
 * connection factory: no BroadWorks connection, no authentication, and no running server are involved.
 * This makes it safe to invoke right after a deployment to show which capabilities the freshly deployed
 * server exposes.</p>
 *
 * <p>The BroadWorks MCP server exposes tools only; it registers no MCP resources or prompts. The
 * {@code resources/list} and {@code prompts/list} responses are therefore spec-compliant but carry
 * empty arrays, mirroring what the live server returns for those methods.</p>
 *
 * <p>Each individual response is the {@code result} of the corresponding JSON-RPC listing, e.g.
 * {@code {"jsonrpc":"2.0","id":1,"result":{"tools":[...]}}}. The combined
 * {@link #registrationJson(String)} emits all three as a JSON-RPC 2.0 batch (an array of response
 * objects) so a single call after a deploy advertises the full catalogue. When a server URL is supplied
 * it is surfaced under the optional {@code result._meta.serverUrl} field of each response so the output
 * can be tied back to the concrete deployed endpoint without breaking JSON-RPC/MCP compliance.</p>
 */
public final class McpToolsListDumper {

    /**
     * Command-line flag on the application entry point that renders the capability listing responses
     * and exits instead of booting the Spring context. An optional server URL may follow it.
     */
    public static final String DUMP_TOOLS_FLAG = "--dump-tools";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpToolsListDumper() {
    }

    /**
     * Builds the combined MCP capability listing as a pretty-printed JSON-RPC 2.0 batch response
     * containing the {@code tools/list}, {@code resources/list} and {@code prompts/list} responses.
     *
     * @param serverUrl the deployed MCP endpoint URL to advertise under each response's
     *                  {@code result._meta.serverUrl}, or {@code null}/blank to omit it.
     * @return the pretty-printed JSON-RPC batch of the three listing responses.
     */
    public static String registrationJson(String serverUrl) {
        final ArrayNode batch = MAPPER.createArrayNode();
        batch.add(toolsListResponse(serverUrl));
        batch.add(resourcesListResponse(serverUrl));
        batch.add(promptsListResponse(serverUrl));
        return pretty(batch);
    }

    /**
     * Builds the MCP {@code tools/list} JSON-RPC response as a pretty-printed JSON string.
     *
     * @param serverUrl the deployed MCP endpoint URL to advertise under {@code result._meta.serverUrl},
     *                  or {@code null}/blank to omit it.
     * @return the pretty-printed JSON-RPC {@code tools/list} response.
     */
    public static String toolsListJson(String serverUrl) {
        return pretty(toolsListResponse(serverUrl));
    }

    /**
     * Builds the MCP {@code resources/list} JSON-RPC response as a pretty-printed JSON string. The
     * BroadWorks MCP server registers no resources, so the {@code resources} array is empty.
     *
     * @param serverUrl the deployed MCP endpoint URL to advertise under {@code result._meta.serverUrl},
     *                  or {@code null}/blank to omit it.
     * @return the pretty-printed JSON-RPC {@code resources/list} response.
     */
    public static String resourcesListJson(String serverUrl) {
        return pretty(resourcesListResponse(serverUrl));
    }

    /**
     * Builds the MCP {@code prompts/list} JSON-RPC response as a pretty-printed JSON string. The
     * BroadWorks MCP server registers no prompts, so the {@code prompts} array is empty.
     *
     * @param serverUrl the deployed MCP endpoint URL to advertise under {@code result._meta.serverUrl},
     *                  or {@code null}/blank to omit it.
     * @return the pretty-printed JSON-RPC {@code prompts/list} response.
     */
    public static String promptsListJson(String serverUrl) {
        return pretty(promptsListResponse(serverUrl));
    }

    private static ObjectNode toolsListResponse(String serverUrl) {
        final MethodToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(new ConnectionTools(null), new ServiceProviderTools(null),
                        new GroupTools(null), new UserTools(null), new ServicePackTools(null),
                        new ServiceTools(null))
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
        return response(1, result, serverUrl);
    }

    private static ObjectNode resourcesListResponse(String serverUrl) {
        final ObjectNode result = MAPPER.createObjectNode();
        result.set("resources", MAPPER.createArrayNode());
        return response(2, result, serverUrl);
    }

    private static ObjectNode promptsListResponse(String serverUrl) {
        final ObjectNode result = MAPPER.createObjectNode();
        result.set("prompts", MAPPER.createArrayNode());
        return response(3, result, serverUrl);
    }

    private static ObjectNode response(int id, ObjectNode result, String serverUrl) {
        if (serverUrl != null && !serverUrl.isBlank()) {
            final ObjectNode meta = MAPPER.createObjectNode();
            meta.put("serverUrl", serverUrl.trim());
            result.set("_meta", meta);
        }

        final ObjectNode response = MAPPER.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.set("result", result);
        return response;
    }

    private static String pretty(Object node) {
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
    }

    /**
     * Prints the combined {@code tools/list} + {@code resources/list} + {@code prompts/list} responses
     * to standard out. An optional first argument is the deployed server URL to advertise.
     */
    public static void main(String[] args) {
        final String serverUrl = args != null && args.length > 0 ? args[0] : null;
        System.out.println(registrationJson(serverUrl));
    }
}
