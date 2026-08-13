package co.pitayagroup.mcp.broadworks.web;

import java.nio.charset.StandardCharsets;
import java.util.List;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link McpEndpointLoggingFilter}: it must record the HTTP method, response status
 * and the (secret-free) JSON-RPC method/tool, correlate log lines via the MDC, and — critically —
 * never leak the bearer token or tool arguments (which can contain a BroadWorks password).
 */
class McpEndpointLoggingFilterTest {

    private final McpEndpointLoggingFilter filter =
            new McpEndpointLoggingFilter(JsonMapper.builder().build());

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        logger = (Logger) LoggerFactory.getLogger(McpEndpointLoggingFilter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        MDC.clear();
    }

    @Test
    void logsMethodStatusAndRpcWithoutLeakingTokenOrArguments() throws Exception {
        final String secretPassword = "sup3r-s3cret-bw-pw";
        final String token = "opaque-bearer-token-value-1234567890";
        final String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"broadworks_add_connection\","
                + "\"arguments\":{\"password\":\"" + secretPassword + "\"}}}";

        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("Authorization", "Bearer " + token);
        request.addHeader("Mcp-Session-Id", "session-abc");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));

        final MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(200);

        final boolean[] correlationIdSeenDownstream = {false};
        filter.doFilter(request, response, (req, res) -> {
            // Simulate the MCP handler consuming the request body (populates the cached content).
            req.getInputStream().readAllBytes();
            correlationIdSeenDownstream[0] = MDC.get(McpEndpointLoggingFilter.REQUEST_ID) != null;
        });

        final String allMessages = allFormattedMessages();

        assertThat(correlationIdSeenDownstream[0])
                .as("correlation id must be present in the MDC for downstream log lines").isTrue();
        assertThat(allMessages)
                .contains("method=POST")
                .contains("uri=/mcp")
                .contains("status=200")
                .contains("tools/call")
                .contains("broadworks_add_connection");

        // The bearer token and tool arguments (password) must never be logged.
        assertThat(allMessages).doesNotContain(token);
        assertThat(allMessages).doesNotContain(secretPassword);
        assertThat(allMessages).doesNotContain("arguments");

        // The MDC must be cleared once the request completes.
        assertThat(MDC.get(McpEndpointLoggingFilter.REQUEST_ID)).isNull();
        assertThat(MDC.get(McpEndpointLoggingFilter.SESSION_ID)).isNull();
    }

    @Test
    void promotesClientErrorsToWarn() throws Exception {
        final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        final MockHttpServletResponse response = new MockHttpServletResponse();
        response.setStatus(401);

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(appender.list)
                .anyMatch(event -> event.getLevel() == Level.WARN
                        && event.getFormattedMessage().contains("status=401"));
    }

    private String allFormattedMessages() {
        final List<ILoggingEvent> events = appender.list;
        final StringBuilder sb = new StringBuilder();
        for (ILoggingEvent event : events) {
            sb.append(event.getFormattedMessage()).append('\n');
        }
        return sb.toString();
    }
}
