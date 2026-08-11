package com.broadworks.mcp.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import com.broadworks.mcp.auth.store.Session;
import com.broadworks.mcp.auth.store.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the full application on a real HTTP port (in-memory stores) and drives the streamable-HTTP
 * MCP endpoint the way a real client does: {@code initialize} then {@code tools/list}. Verifies that
 * every BroadWorks {@code @Tool} is actually returned over the wire (guards against regressions where
 * the tool beans are registered but not exposed).
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
                Instant.now().plus(1, ChronoUnit.HOURS), null, Instant.now()));

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
        final String sessionId = init.headers().firstValue("mcp-session-id").orElseThrow();

        final HttpResponse<String> list = client.send(
                HttpRequest.newBuilder(URI.create(base))
                        .header("Authorization", "Bearer tok-mcp")
                        .header("Mcp-Session-Id", sessionId)
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
                .contains("broadworks_list_groups")
                .contains("broadworks_get_group");
    }
}
