package co.pitayagroup.mcp.broadworks.mcp;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies {@link McpToolsListDumper} emits well-formed, spec-compliant MCP {@code tools/list},
 * {@code resources/list} and {@code prompts/list} JSON-RPC responses covering every BroadWorks
 * {@code @McpTool}, entirely offline (no server, no auth).
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
                "broadworks_flush_cache",
                "broadworks_list_service_providers",
                "broadworks_get_service_provider",
                "broadworks_modify_service_provider",
                "broadworks_create_service_provider",
                "broadworks_delete_service_provider",
                "broadworks_list_groups",
                "broadworks_get_group",
                "broadworks_modify_group",
                "broadworks_create_group",
                "broadworks_delete_group",
                "broadworks_list_users",
                "broadworks_get_user",
                "broadworks_modify_user",
                "broadworks_create_user",
                "broadworks_delete_user",
                "broadworks_list_service_packs",
                "broadworks_get_service_pack",
                "broadworks_create_service_pack",
                "broadworks_modify_service_pack",
                "broadworks_delete_service_pack",
                "broadworks_get_service_provider_service_authorization",
                "broadworks_modify_service_provider_service_authorization",
                "broadworks_get_group_service_authorization",
                "broadworks_modify_group_service_authorization",
                "broadworks_assign_group_services",
                "broadworks_unassign_group_services",
                "broadworks_get_user_assigned_services",
                "broadworks_assign_user_services",
                "broadworks_unassign_user_services",
                "broadworks_get_domain_model");
    }

    @Test
    void rendersEmptyJsonRpcResourcesList() {
        final JsonNode root = MAPPER.readTree(McpToolsListDumper.resourcesListJson(null));

        assertThat(root.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(root.get("id").asInt()).isEqualTo(2);
        final JsonNode resources = root.get("result").get("resources");
        assertThat(resources.isArray()).isTrue();
        assertThat(resources.isEmpty()).isTrue();
    }

    @Test
    void rendersEmptyJsonRpcPromptsList() {
        final JsonNode root = MAPPER.readTree(McpToolsListDumper.promptsListJson(null));

        assertThat(root.get("jsonrpc").asString()).isEqualTo("2.0");
        assertThat(root.get("id").asInt()).isEqualTo(3);
        final JsonNode prompts = root.get("result").get("prompts");
        assertThat(prompts.isArray()).isTrue();
        assertThat(prompts.isEmpty()).isTrue();
    }

    @Test
    void registrationBatchContainsToolsResourcesAndPrompts() {
        final JsonNode batch = MAPPER.readTree(McpToolsListDumper.registrationJson(null));

        assertThat(batch.isArray()).isTrue();
        assertThat(batch.size()).isEqualTo(3);

        // The batch is an ordered JSON-RPC 2.0 batch response: tools (id 1), resources (id 2), prompts (id 3).
        final JsonNode toolsResponse = batch.get(0);
        assertThat(toolsResponse.get("id").asInt()).isEqualTo(1);
        assertThat(toolsResponse.get("result").get("tools").isArray()).isTrue();
        assertThat(toolsResponse.get("result").get("tools").isEmpty()).isFalse();

        final JsonNode resourcesResponse = batch.get(1);
        assertThat(resourcesResponse.get("id").asInt()).isEqualTo(2);
        assertThat(resourcesResponse.get("result").get("resources").isArray()).isTrue();

        final JsonNode promptsResponse = batch.get(2);
        assertThat(promptsResponse.get("id").asInt()).isEqualTo(3);
        assertThat(promptsResponse.get("result").get("prompts").isArray()).isTrue();
    }

    @Test
    void registrationBatchAdvertisesServerUrlOnEveryResponse() {
        final JsonNode batch =
                MAPPER.readTree(McpToolsListDumper.registrationJson(" https://mcp.example.com/mcp "));

        for (JsonNode response : batch) {
            assertThat(response.get("result").get("_meta").get("serverUrl").asString())
                    .isEqualTo("https://mcp.example.com/mcp");
        }
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
