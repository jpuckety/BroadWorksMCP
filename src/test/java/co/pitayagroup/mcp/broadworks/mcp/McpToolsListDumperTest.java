package co.pitayagroup.mcp.broadworks.mcp;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link McpToolsListDumper} emits a well-formed, spec-compliant MCP {@code tools/list}
 * JSON-RPC response covering every BroadWorks {@code @Tool}, entirely offline (no server, no auth).
 */
class McpToolsListDumperTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void rendersJsonRpcToolsListWithEveryTool() {
        final JsonNode root = MAPPER.readTree(McpToolsListDumper.toolsListJson(null));

        assertThat(root.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(root.get("id").asInt()).isEqualTo(1);

        final JsonNode tools = root.get("result").get("tools");
        assertThat(tools.isArray()).isTrue();

        final List<String> names = new ArrayList<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asString());
            // Each tool carries a non-blank description and an object (not stringified) input schema.
            assertThat(tool.get("description").asString()).isNotBlank();
            assertThat(tool.get("inputSchema").isObject()).isTrue();
        }

        assertThat(names).containsExactlyInAnyOrder(
                "broadworks_add_connection",
                "broadworks_list_connections",
                "broadworks_delete_connection",
                "broadworks_list_service_providers",
                "broadworks_get_service_provider",
                "broadworks_list_groups",
                "broadworks_get_group",
                "broadworks_list_users",
                "broadworks_get_user");
    }

    @Test
    void omitsMetaWhenNoServerUrlGiven() {
        final JsonNode result = MAPPER.readTree(McpToolsListDumper.toolsListJson("  ")).get("result");
        assertThat(result.has("_meta")).isFalse();
    }

    @Test
    void advertisesServerUrlUnderMetaWhenGiven() {
        final JsonNode result =
                MAPPER.readTree(McpToolsListDumper.toolsListJson(" https://mcp.example.com/mcp ")).get("result");
        assertThat(result.get("_meta").get("serverUrl").asString()).isEqualTo("https://mcp.example.com/mcp");
    }
}
