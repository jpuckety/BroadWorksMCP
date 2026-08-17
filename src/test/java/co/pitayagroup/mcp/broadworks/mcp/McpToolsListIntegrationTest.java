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
 * Boots the full application on a real HTTP port (in-memory stores) and drives the stateless-HTTP
 * MCP endpoint the way a real client does: {@code initialize} then {@code tools/list}. Verifies that
 * every BroadWorks {@code @Tool} is actually returned over the wire (guards against regressions where
 * the tool beans are registered but not exposed).
 *
 * <p>The server runs the {@code STATELESS} transport (see {@code application.yml}), so each POST is
 * self-contained: {@code initialize} returns a plain JSON response with no {@code Mcp-Session-Id}
 * header and no session id is threaded through the follow-up {@code tools/list} call.</p>
 */
@SpringBootTest(properties = {"broadworks.storage.backend=IN_MEMORY"},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpToolsListIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SessionStore sessionStore;

    @Test
    void toolsListReturnsAllBroadWorksToolsOverStatelessHttp() throws Exception {
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
        // STATELESS transport mints no session id; there is no Mcp-Session-Id header to thread through.
        assertThat(init.headers().firstValue("mcp-session-id")).isEmpty();

        final HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("Authorization", "Bearer tok-mcp")
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream")
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
                .contains("broadworks_list_groups")
                .contains("broadworks_get_group")
                .contains("broadworks_list_users")
                .contains("broadworks_get_user");
    }
}
