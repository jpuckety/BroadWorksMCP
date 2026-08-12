package com.broadworks.mcp.web;

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

/**
 * Access logging for the OAuth / discovery surface ({@code /.well-known/**}, {@code /oauth/register},
 * {@code /oauth2/**}, {@code /login/**}).
 *
 * <p>Without it a client that gives up during discovery, registration, or the authorization-code
 * flow leaves no trace at all: only the MCP transport was access-logged, so the sole evidence of a
 * failed connection attempt was the initial {@code 401} on {@code /mcp}. Every request here is logged
 * with method, path, response status and duration (promoted to {@code WARN}/{@code ERROR} for
 * {@code 4xx}/{@code 5xx}), plus the {@code Location} header on redirects so the hand-off to Google
 * (or back to the client) is visible.</p>
 *
 * <p><b>Never logs secrets.</b> The request body is never read — the token endpoint's body carries
 * authorization codes, PKCE verifiers and refresh tokens — and of the query string only the
 * non-secret {@code client_id}, {@code redirect_uri} and {@code resource} parameters are surfaced.
 * The bearer token is reported as present/absent only.</p>
 */
@Slf4j
public class OAuthEndpointLoggingFilter extends OncePerRequestFilter {

    /** Query parameters safe to log: client-supplied identifiers, never credentials. */
    private static final String[] LOGGED_PARAMETERS = {"client_id", "redirect_uri", "resource", "error"};

    private static final String BEARER_PREFIX = "Bearer ";

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        final long startNanos = System.nanoTime();
        final String requestId = UUID.randomUUID().toString().substring(0, 8);
        final boolean stamped = MDC.get(McpEndpointLoggingFilter.REQUEST_ID) == null;
        if (stamped) {
            MDC.put(McpEndpointLoggingFilter.REQUEST_ID, requestId);
        }

        log.debug("OAuth request received method={} uri={} params=[{}] bearer={}",
                request.getMethod(), request.getRequestURI(), describeParameters(request),
                hasBearerToken(request));

        try {
            filterChain.doFilter(request, response);
        } finally {
            final long durationMs = (System.nanoTime() - startNanos) / 1_000_000L;
            final int status = response.getStatus();
            final String location = response.getHeader(HttpHeaders.LOCATION);
            if (status >= 500) {
                log.error("OAuth request completed method={} uri={} params=[{}] status={} durationMs={}",
                        request.getMethod(), request.getRequestURI(), describeParameters(request),
                        status, durationMs);
            } else if (status >= 400) {
                log.warn("OAuth request rejected method={} uri={} params=[{}] status={} durationMs={}",
                        request.getMethod(), request.getRequestURI(), describeParameters(request),
                        status, durationMs);
            } else {
                log.info("OAuth request completed method={} uri={} params=[{}] status={} durationMs={} location={}",
                        request.getMethod(), request.getRequestURI(), describeParameters(request),
                        status, durationMs, redirectTarget(location));
            }
            if (stamped) {
                MDC.remove(McpEndpointLoggingFilter.REQUEST_ID);
            }
        }
    }

    /**
     * @return the {@link #LOGGED_PARAMETERS} present on the request, comma separated, or {@code "-"}.
     * Reads the query string only, so a form-encoded token request is never inspected.
     */
    private static String describeParameters(HttpServletRequest request) {
        final String query = request.getQueryString();
        if (query == null || query.isBlank()) {
            return "-";
        }
        final StringBuilder described = new StringBuilder();
        for (String pair : query.split("&")) {
            final int equals = pair.indexOf('=');
            final String name = equals < 0 ? pair : pair.substring(0, equals);
            for (String logged : LOGGED_PARAMETERS) {
                if (logged.equals(name)) {
                    if (!described.isEmpty()) {
                        described.append(',');
                    }
                    described.append(pair);
                }
            }
        }
        return described.isEmpty() ? "-" : described.toString();
    }

    /**
     * @return the redirect target without its query string, so authorization codes and upstream
     * {@code state}/{@code nonce} values are not logged; {@code "-"} when the response is no redirect.
     */
    private static String redirectTarget(String location) {
        if (location == null || location.isBlank()) {
            return "-";
        }
        final int query = location.indexOf('?');
        return query < 0 ? location : location.substring(0, query) + "?...";
    }

    private static boolean hasBearerToken(HttpServletRequest request) {
        final String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        return header != null && header.regionMatches(true, 0, BEARER_PREFIX, 0, BEARER_PREFIX.length());
    }
}
