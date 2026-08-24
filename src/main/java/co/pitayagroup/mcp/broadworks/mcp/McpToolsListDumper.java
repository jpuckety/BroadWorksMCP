package co.pitayagroup.mcp.broadworks.mcp;

import java.lang.reflect.Method;

import co.pitayagroup.mcp.broadworks.mcp.tools.CacheTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.DomainModelTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.GroupTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServicePackTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceProviderTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.UserTools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Renders the BroadWorks MCP capability catalogue as spec-compliant MCP {@code tools/list},
 * {@code resources/list} and {@code prompts/list} JSON-RPC responses, entirely offline.
 *
 * <p>It reflects every {@code @McpTool} method the annotation scanner exposes at runtime. Because
 * only the tool <em>definitions</em> (name, description, JSON input schema) are needed, no
 * BroadWorks connection, authentication, or running server is involved. This makes it safe to
 * invoke right after a deployment to show which capabilities the freshly deployed server
 * exposes.</p>
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

    private static final Class<?>[] TOOL_CLASSES = {
            ConnectionTools.class,
            CacheTools.class,
            ServiceProviderTools.class,
            GroupTools.class,
            UserTools.class,
            ServicePackTools.class,
            ServiceTools.class,
            DomainModelTools.class
    };

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
        final ArrayNode tools = MAPPER.createArrayNode();
        for (Class<?> type : TOOL_CLASSES) {
            addMcpAnnotatedTools(tools, type);
        }

        final ObjectNode result = MAPPER.createObjectNode();
        result.set("tools", tools);
        return response(1, result, serverUrl);
    }

    private static void addMcpAnnotatedTools(ArrayNode tools, Class<?> type) {
        for (Method method : type.getDeclaredMethods()) {
            final McpTool annotation = method.getAnnotation(McpTool.class);
            if (annotation == null) {
                continue;
            }
            final String name = annotation.name().isBlank() ? method.getName() : annotation.name();
            addTool(tools, name, annotation.description(), McpJsonSchemaGenerator.generateForMethodInput(method));
        }
    }

    private static void addTool(ArrayNode tools, String name, String description, String schema) {
        final ObjectNode tool = MAPPER.createObjectNode();
        tool.put("name", name);
        tool.put("description", description == null ? "" : description);
        tool.set("inputSchema",
                schema == null || schema.isBlank() ? MAPPER.createObjectNode() : MAPPER.readTree(schema));
        tools.add(tool);
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
