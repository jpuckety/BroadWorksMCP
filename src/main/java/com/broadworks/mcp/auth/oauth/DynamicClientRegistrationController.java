package com.broadworks.mcp.auth.oauth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.broadworks.mcp.auth.store.RegisteredClientRecord;
import com.broadworks.mcp.auth.store.SessionStore;
import com.broadworks.mcp.config.AuthTokenProperties;
import com.broadworks.mcp.config.RedirectAllowlistProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotEmpty;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Minimal RFC 7591 Dynamic Client Registration endpoint at {@code /oauth/register}, restricted to
 * <b>public clients</b> (no client secret is ever issued). Registered clients are persisted durably
 * via the {@link SessionStore} with the configured lifetime.
 *
 * <p>Redirect URIs are validated against the allow-list: HTTPS and custom-scheme URIs must match a
 * configured prefix; loopback HTTP is always permitted.</p>
 */
@RestController
@RequiredArgsConstructor
public class DynamicClientRegistrationController {

    private static final List<String> DEFAULT_GRANT_TYPES = List.of("authorization_code", "refresh_token");
    private static final List<String> DEFAULT_SCOPES = List.of("openid", "email", "profile");

    private final SessionStore sessionStore;
    private final AuthTokenProperties tokenProperties;
    private final RedirectAllowlistProperties redirectAllowlist;

    @PostMapping(path = "/oauth/register",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> register(@RequestBody RegistrationRequest request) {
        final List<String> redirectUris = request.redirectUris() == null ? List.of() : request.redirectUris();
        if (redirectUris.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "redirect_uris is required");
        }
        for (String uri : redirectUris) {
            if (!redirectAllowlist.isAllowed(uri)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "redirect_uri not permitted by allow-list");
            }
        }

        final List<String> grantTypes = request.grantTypes() == null || request.grantTypes().isEmpty()
                ? DEFAULT_GRANT_TYPES : request.grantTypes();
        final List<String> scopes = scopesFrom(request.scope());
        final Instant now = Instant.now();
        final Instant expiresAt = now.plus(tokenProperties.registeredClientTtl());
        final String clientId = UUID.randomUUID().toString();

        final RegisteredClientRecord record = new RegisteredClientRecord(
                clientId,
                request.clientName(),
                redirectUris,
                scopes,
                grantTypes,
                RegisteredClientRecord.PUBLIC_CLIENT_AUTH_METHOD,
                now,
                expiresAt);
        sessionStore.saveClient(record);

        final Map<String, Object> response = new LinkedHashMap<>();
        response.put("client_id", clientId);
        response.put("client_id_issued_at", now.getEpochSecond());
        response.put("client_secret_expires_at", 0); // public client: never expires as a secret
        response.put("token_endpoint_auth_method", RegisteredClientRecord.PUBLIC_CLIENT_AUTH_METHOD);
        response.put("grant_types", grantTypes);
        response.put("redirect_uris", redirectUris);
        response.put("scope", String.join(" ", scopes));
        if (request.clientName() != null) {
            response.put("client_name", request.clientName());
        }
        return response;
    }

    private static List<String> scopesFrom(String scope) {
        if (scope == null || scope.isBlank()) {
            return DEFAULT_SCOPES;
        }
        final List<String> scopes = new ArrayList<>();
        for (String token : scope.split("\\s+")) {
            if (!token.isBlank()) {
                scopes.add(token);
            }
        }
        return scopes.isEmpty() ? DEFAULT_SCOPES : scopes;
    }

    /**
     * RFC 7591 client-registration request (subset).
     *
     * @param redirectUris registered redirect URIs (required).
     * @param clientName   optional human-readable client name.
     * @param grantTypes   requested grant types (defaults to auth-code + refresh).
     * @param scope        space-delimited scopes.
     */
    public record RegistrationRequest(
            @JsonProperty("redirect_uris") @NotEmpty List<String> redirectUris,
            @JsonProperty("client_name") String clientName,
            @JsonProperty("grant_types") List<String> grantTypes,
            @JsonProperty("scope") String scope
    ) {
    }
}
