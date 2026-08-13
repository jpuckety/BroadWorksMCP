package co.pitayagroup.mcp.broadworks.web;

import java.io.IOException;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Request/response access logging for the MCP endpoints ({@code /mcp} and the legacy {@code /sse}
 * transport) to make client interactions troubleshootable.
 *
 * <p>For every request this filter:</p>
 * <ul>
 *   <li>stamps a short correlation id ({@value #REQUEST_ID}) and, when present, the client's
 *       {@code Mcp-Session-Id} ({@value #SESSION_ID}) into the SLF4J {@link MDC} so that every log
 *       line emitted while handling the request (this filter, the token introspector, the bearer
 *       challenge, and the tools) can be correlated;</li>
 *   <li>logs a concise entry line (method, URI, whether a bearer token was supplied, session id);</li>
 *   <li>logs a completion line with the response status and wall-clock duration, promoted to
 *       {@code WARN}/{@code ERROR} for {@code 4xx}/{@code 5xx} so failures stand out.</li>
 * </ul>
 *
 * <p><b>Never logs secrets.</b> The bearer token value is only reported as present/absent, and only
 * the JSON-RPC envelope fields safe to surface are logged: the {@code method}, the request {@code id}
 * and — for {@code tools/call} — the tool {@code name}. The tool {@code arguments} are deliberately
 * omitted because they may carry credentials (e.g. a BroadWorks password on
 * {@code broadworks_add_connection}).</p>
 */
@Slf4j
public class McpEndpointLoggingFilter extends OncePerRequestFilter {

    /** MDC key holding the per-request correlation id. */
    public static final String REQUEST_ID = "mcpRequestId";
    /** MDC key holding the MCP session id (when the client supplied one). */
    public static final String SESSION_ID = "mcpSessionId";

    private static final String SESSION_HEADER = "Mcp-Session-Id";
    private static final String BEARER_PREFIX = "Bearer ";
    /** Cap defensive JSON parsing of the request body to a sane size. */
    private static final int MAX_PARSE_BYTES = 64 * 1024;

    private final ObjectMapper objectMapper;

    public McpEndpointLoggingFilter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        final long startNanos = System.nanoTime();
        final String requestId = UUID.randomUUID().toString().substring(0, 8);
        final String sessionId = request.getHeader(SESSION_HEADER);
        final boolean bearer = hasBearerToken(request);

        MDC.put(REQUEST_ID, requestId);
        if (sessionId != null && !sessionId.isBlank()) {
            MDC.put(SESSION_ID, sessionId);
        }

        // Wrap so the request body can be inspected (safely) after the handler has consumed it.
        // Cap the cached content so large payloads are not buffered wholesale.
        final ContentCachingRequestWrapper wrapped = new ContentCachingRequestWrapper(request, MAX_PARSE_BYTES);

        log.debug("MCP request received method={} uri={} bearer={} sessionId={} contentType={}",
                request.getMethod(), request.getRequestURI(), bearer,
                sessionId == null ? "-" : sessionId, request.getContentType());

        try {
            filterChain.doFilter(wrapped, response);
        } finally {
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            final int status = response.getStatus();
            final String rpc = describeJsonRpc(wrapped);
            if (status >= 500) {
                log.error("MCP request completed method={} uri={} rpc=[{}] status={} durationMs={} bearer={}",
                        request.getMethod(), request.getRequestURI(), rpc, status, durationMs, bearer);
            } else if (status >= 400) {
                log.warn("MCP request rejected method={} uri={} rpc=[{}] status={} durationMs={} bearer={}",
                        request.getMethod(), request.getRequestURI(), rpc, status, durationMs, bearer);
            } else {
                log.info("MCP request completed method={} uri={} rpc=[{}] status={} durationMs={}",
                        request.getMethod(), request.getRequestURI(), rpc, status, durationMs);
            }
            MDC.remove(REQUEST_ID);
            MDC.remove(SESSION_ID);
        }
    }

    private static boolean hasBearerToken(HttpServletRequest request) {
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }

    /**
     * Extracts a compact, secret-free description of the JSON-RPC envelope from the (already consumed)
     * request body: {@code method}, {@code id} and, for {@code tools/call}, the tool {@code name}.
     * Never surfaces {@code params.arguments}. Returns {@code "-"} when there is no body and
     * {@code "unparseable"} when the body is not valid JSON-RPC.
     */
    private String describeJsonRpc(ContentCachingRequestWrapper wrapped) {
        final byte[] body = wrapped.getContentAsByteArray();
        if (body.length == 0 || body.length > MAX_PARSE_BYTES) {
            return "-";
        }
        try {
            final JsonNode root = objectMapper.readTree(body);
            if (root == null || !root.isObject()) {
                return "-";
            }
            final String method = textOrNull(root, "method");
            final StringBuilder description = new StringBuilder("method=").append(method == null ? "?" : method);
            final JsonNode id = root.get("id");
            if (id != null && !id.isNull()) {
                description.append(",id=").append(id.asText());
            }
            if ("tools/call".equals(method)) {
                final JsonNode params = root.get("params");
                if (params != null && params.hasNonNull("name")) {
                    // Only the tool name is safe to log; arguments may contain secrets.
                    description.append(",tool=").append(params.get("name").asText());
                }
            }
            return description.toString();
        } catch (RuntimeException ex) {
            return "unparseable";
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}
