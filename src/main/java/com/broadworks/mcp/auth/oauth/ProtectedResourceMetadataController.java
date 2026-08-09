package com.broadworks.mcp.auth.oauth;

import java.util.List;
import java.util.Map;

import com.broadworks.mcp.config.PublicBaseUrlProperties;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * RFC 9728 OAuth 2.0 Protected Resource Metadata endpoint.
 *
 * <p>Advertises this MCP server as a protected resource and points clients at the authorization
 * server. Exposed at {@code /.well-known/oauth-protected-resource} and its trailing-slash variant so
 * MCP clients discovering via the {@code WWW-Authenticate: ... resource_metadata=...} challenge can
 * find it.</p>
 */
@RestController
@RequiredArgsConstructor
public class ProtectedResourceMetadataController {

    private final PublicBaseUrlProperties publicBaseUrl;

    @GetMapping(path = {"/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> metadata() {
        final String baseUrl = publicBaseUrl.baseUrl();
        return Map.of(
                "resource", baseUrl,
                "authorization_servers", List.of(baseUrl),
                "bearer_methods_supported", List.of("header"),
                "scopes_supported", List.of("openid", "email", "profile"));
    }
}
