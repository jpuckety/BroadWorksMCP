package com.broadworks.mcp.auth.oauth;

import java.io.IOException;

import com.broadworks.mcp.config.PublicBaseUrlProperties;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;

/**
 * Resource-Server authentication entry point that returns {@code 401 Unauthorized} with a
 * {@code WWW-Authenticate: Bearer realm="mcp", resource_metadata="..."} challenge so MCP clients can
 * discover how to authenticate (RFC 9728). Renders a short HTML page for browsers and a plain
 * message otherwise.
 */
@RequiredArgsConstructor
public class BearerChallengeEntryPoint implements AuthenticationEntryPoint {

    private static final String REALM = "mcp";
    private static final String METADATA_PATH = "/.well-known/oauth-protected-resource";

    private final PublicBaseUrlProperties publicBaseUrl;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        final String resourceMetadata = publicBaseUrl.baseUrl() + METADATA_PATH;
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE,
                "Bearer realm=\"" + REALM + "\", resource_metadata=\"" + resourceMetadata + "\"");

        final String accept = request.getHeader(HttpHeaders.ACCEPT);
        if (accept != null && accept.contains(MediaType.TEXT_HTML_VALUE)) {
            response.setContentType(MediaType.TEXT_HTML_VALUE);
            response.getWriter().write("""
                    <!DOCTYPE html><html><head><title>Authentication required</title></head>
                    <body><h1>401 Unauthorized</h1>
                    <p>This MCP resource requires a Bearer access token.</p>
                    <p>Discover how to authenticate at
                    <a href="%s">%s</a>.</p></body></html>""".formatted(resourceMetadata, resourceMetadata));
        } else {
            response.setContentType(MediaType.TEXT_PLAIN_VALUE);
            response.getWriter().write("Unauthorized: Bearer access token required. "
                    + "See " + resourceMetadata);
        }
    }
}
