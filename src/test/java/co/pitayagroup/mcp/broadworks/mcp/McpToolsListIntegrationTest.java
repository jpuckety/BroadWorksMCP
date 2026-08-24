package co.pitayagroup.mcp.broadworks.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application on a real HTTP port (in-memory stores) and drives the streamable-HTTP
 * MCP endpoint the way a real client does: {@code initialize} then {@code tools/list}. Verifies that
 * every BroadWorks tool is actually returned over the wire (guards against regressions where
 * the tool beans are registered but not exposed).
 *
 * <p>The server runs the {@code STREAMABLE} transport (see {@code application.yml}) so elicitation
 * can use a session-capable exchange. {@code initialize} mints an {@code Mcp-Session-Id} that is
 * threaded through the follow-up {@code tools/list} and {@code tools/call} requests.</p>
 */
@SpringBootTest(properties = {"broadworks.storage.backend=IN_MEMORY"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolsListIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SessionStore sessionStore;

    @Test
    void toolsListReturnsAllBroadWorksToolsOverStreamableHttp() throws Exception {
        sessionStore.createSession(new Session(null, "tok-mcp", null, "client-1", "sub-mcp",
                "user@example.com", null, null,
                Instant.now().plus(1, ChronoUnit.HOURS), null, Instant.now(),
                "authz-mcp", "http://localhost:8080/mcp"));

        final HttpClient client = HttpClient.newHttpClient();
        final String base = "http://localhost:" + port + "/mcp";

        final HttpResponse<String> init = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("Authorization", "Bearer tok-mcp")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                        + "\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                                        + "\"clientInfo\":{\"name\":\"probe\",\"version\":\"1\"}}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(init.statusCode()).isEqualTo(200);
        final String sessionId = init.headers().firstValue("mcp-session-id").orElse(null);
        assertThat(sessionId).as("STREAMABLE transport mints a session id on initialize").isNotBlank();
        assertThat(init.body())
                .contains("BroadWorks object model (read this first):")
                .contains("System → Service Provider / Enterprise → Group → User")
                .contains("Authorized")
                .contains("Assigned")
                .contains("serviceProviderId")
                .contains("isEnterprise");

        final HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("Authorization", "Bearer tok-mcp")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(list.statusCode()).isEqualTo(200);
        final String body = list.body();
        System.out.println("LIST_BODY=" + body);

        assertThat(body)
                .contains("broadworks_add_connection")
                .contains("broadworks_list_connections")
                .contains("broadworks_delete_connection")
                .contains("broadworks_list_service_providers")
                .contains("broadworks_get_service_provider")
                .contains("broadworks_modify_service_provider")
                .contains("broadworks_create_service_provider")
                .contains("broadworks_list_groups")
                .contains("broadworks_get_group")
                .contains("broadworks_modify_group")
                .contains("broadworks_create_group")
                .contains("broadworks_list_users")
                .contains("broadworks_get_user")
                .contains("broadworks_modify_user")
                .contains("broadworks_create_user")
                .contains("broadworks_list_service_packs")
                .contains("broadworks_get_service_pack")
                .contains("broadworks_create_service_pack")
                .contains("broadworks_modify_service_pack")
                .contains("broadworks_delete_service_pack")
                .contains("broadworks_get_service_provider_service_authorization")
                .contains("broadworks_modify_service_provider_service_authorization")
                .contains("broadworks_get_group_service_authorization")
                .contains("broadworks_modify_group_service_authorization")
                .contains("broadworks_assign_group_services")
                .contains("broadworks_unassign_group_services")
                .contains("broadworks_get_user_assigned_services")
                .contains("broadworks_assign_user_services")
                .contains("broadworks_unassign_user_services")
                .contains("broadworks_get_domain_model");

        final HttpResponse<String> call = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("Authorization", "Bearer tok-mcp")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
                        .header("Mcp-Session-Id", sessionId)
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                                        + "\"name\":\"broadworks_get_domain_model\",\"arguments\":{}}}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(call.statusCode()).isEqualTo(200);
        assertThat(call.body())
                .contains("Access Device")
                .contains("many-to-many")
                .contains("Authorized")
                .contains("serviceProviderId");
    }
}
