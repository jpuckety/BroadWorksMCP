package com.broadworks.mcp.auth.identity;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import com.broadworks.mcp.config.OidcProperties;
import com.broadworks.mcp.config.PublicBaseUrlProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Default {@link IdentityProvider} targeting an OpenID Connect provider (Google by default).
 *
 * <p>OIDC endpoints are discovered from the issuer's {@code /.well-known/openid-configuration} and
 * memoized. ID tokens are verified with a JWKS-backed {@link JwtDecoder} (signature + expiry) plus
 * explicit issuer / audience / {@code sub} / {@code email_verified} checks. Signature verification
 * is intentionally decoupled from the claim checks so the latter can be unit-tested offline by
 * injecting a decoder.</p>
 */
public class GoogleIdentityProvider implements IdentityProvider {

    private static final String DISCOVERY_SUFFIX = "/.well-known/openid-configuration";

    private final OidcProperties oidcProperties;
    private final PublicBaseUrlProperties publicBaseUrl;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    /** Optional injected decoder (tests); when {@code null} a JWKS decoder is built lazily. */
    private volatile JwtDecoder jwtDecoder;
    private volatile OidcEndpoints endpoints;

    public GoogleIdentityProvider(OidcProperties oidcProperties,
                                  PublicBaseUrlProperties publicBaseUrl,
                                  HttpClient httpClient,
                                  ObjectMapper objectMapper) {
        this(oidcProperties, publicBaseUrl, httpClient, objectMapper, null);
    }

    /** Constructor allowing a decoder override for offline unit testing of claim verification. */
    public GoogleIdentityProvider(OidcProperties oidcProperties,
                                  PublicBaseUrlProperties publicBaseUrl,
                                  HttpClient httpClient,
                                  ObjectMapper objectMapper,
                                  JwtDecoder jwtDecoderOverride) {
        this.oidcProperties = oidcProperties;
        this.publicBaseUrl = publicBaseUrl;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.jwtDecoder = jwtDecoderOverride;
    }

    @Override
    public URI authCodeUrl(String state, String codeChallenge) {
        final OidcEndpoints ep = endpoints();
        final StringBuilder url = new StringBuilder(ep.authorizationEndpoint());
        url.append(ep.authorizationEndpoint().contains("?") ? '&' : '?');
        url.append("response_type=code");
        appendParam(url, "client_id", oidcProperties.clientId());
        appendParam(url, "redirect_uri", publicBaseUrl.callbackUri());
        appendParam(url, "scope", String.join(" ", oidcProperties.scopes()));
        appendParam(url, "state", state);
        appendParam(url, "code_challenge", codeChallenge);
        appendParam(url, "code_challenge_method", "S256");
        // Request offline access so the IdP returns a refresh token (Google semantics).
        appendParam(url, "access_type", "offline");
        return URI.create(url.toString());
    }

    @Override
    public ExchangeResult exchange(String code, String codeVerifier) {
        final OidcEndpoints ep = endpoints();
        final StringJoiner form = new StringJoiner("&");
        form.add(param("grant_type", "authorization_code"));
        form.add(param("code", code));
        form.add(param("redirect_uri", publicBaseUrl.callbackUri()));
        form.add(param("client_id", oidcProperties.clientId()));
        form.add(param("code_verifier", codeVerifier));
        if (oidcProperties.clientSecret() != null && !oidcProperties.clientSecret().isBlank()) {
            form.add(param("client_secret", oidcProperties.clientSecret()));
        }

        final JsonNode json = postForm(ep.tokenEndpoint(), form.toString());
        final String idToken = text(json, "id_token");
        if (idToken == null) {
            throw new IdentityProviderException("Token response did not contain an id_token");
        }
        final IdTokenClaims claims = verifyIdToken(idToken);
        final Instant accessExpiry = json.has("expires_in")
                ? Instant.now().plusSeconds(json.get("expires_in").asLong())
                : null;
        final RawTokens tokens = new RawTokens(idToken, text(json, "access_token"),
                text(json, "refresh_token"), accessExpiry);
        return new ExchangeResult(claims, tokens);
    }

    @Override
    public IdTokenClaims verifyIdToken(String rawIdToken) {
        final Jwt jwt;
        try {
            jwt = jwtDecoder().decode(rawIdToken);
        } catch (JwtException ex) {
            throw new IdentityProviderException("ID token signature/expiry verification failed", ex);
        }

        final String expectedIssuer = expectedIssuer();
        final String issuer = jwt.getIssuer() != null ? jwt.getIssuer().toString() : null;
        if (issuer == null || !issuer.equals(expectedIssuer)) {
            throw new IdentityProviderException("ID token issuer mismatch");
        }

        final List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(oidcProperties.clientId())) {
            throw new IdentityProviderException("ID token audience mismatch");
        }

        final String sub = jwt.getSubject();
        if (sub == null || sub.isBlank()) {
            throw new IdentityProviderException("ID token missing sub claim");
        }

        final Boolean emailVerified = jwt.getClaimAsBoolean("email_verified");
        if (!Boolean.TRUE.equals(emailVerified)) {
            throw new IdentityProviderException("ID token email_verified is not true");
        }

        return new IdTokenClaims(sub, jwt.getClaimAsString("email"), true, issuer,
                oidcProperties.clientId(), jwt.getExpiresAt());
    }

    // ---- discovery / decoder --------------------------------------------

    private OidcEndpoints endpoints() {
        OidcEndpoints local = endpoints;
        if (local == null) {
            synchronized (this) {
                local = endpoints;
                if (local == null) {
                    local = discover();
                    endpoints = local;
                }
            }
        }
        return local;
    }

    private OidcEndpoints discover() {
        final String url = oidcProperties.issuerUri() + DISCOVERY_SUFFIX;
        final JsonNode json = getJson(url);
        return new OidcEndpoints(
                text(json, "authorization_endpoint"),
                text(json, "token_endpoint"),
                text(json, "jwks_uri"),
                text(json, "issuer"));
    }

    private JwtDecoder jwtDecoder() {
        JwtDecoder local = jwtDecoder;
        if (local == null) {
            synchronized (this) {
                local = jwtDecoder;
                if (local == null) {
                    local = NimbusJwtDecoder.withJwkSetUri(endpoints().jwksUri()).build();
                    jwtDecoder = local;
                }
            }
        }
        return local;
    }

    private String expectedIssuer() {
        final OidcEndpoints ep = endpoints;
        if (ep != null && ep.issuer() != null) {
            return ep.issuer();
        }
        return oidcProperties.issuerUri();
    }

    // ---- HTTP helpers ---------------------------------------------------

    private JsonNode getJson(String url) {
        try {
            final HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IdentityProviderException("IdP GET failed with status " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (java.io.IOException ex) {
            throw new IdentityProviderException("IdP GET failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("IdP GET interrupted", ex);
        }
    }

    private JsonNode postForm(String url, String body) {
        try {
            final HttpResponse<String> response = httpClient.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .header("Content-Type", "application/x-www-form-urlencoded")
                            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IdentityProviderException("IdP token exchange failed with status "
                        + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (java.io.IOException ex) {
            throw new IdentityProviderException("IdP token exchange failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderException("IdP token exchange interrupted", ex);
        }
    }

    private static void appendParam(StringBuilder url, String name, String value) {
        url.append('&').append(param(name, value));
    }

    private static String param(String name, String value) {
        return name + "=" + URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        final JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** Discovered OIDC endpoints. */
    private record OidcEndpoints(String authorizationEndpoint, String tokenEndpoint, String jwksUri,
                                 String issuer) {
        OidcEndpoints {
            if (authorizationEndpoint == null || tokenEndpoint == null || jwksUri == null) {
                throw new IdentityProviderException("Incomplete OIDC discovery document: "
                        + Map.of("authorization_endpoint", String.valueOf(authorizationEndpoint),
                        "token_endpoint", String.valueOf(tokenEndpoint),
                        "jwks_uri", String.valueOf(jwksUri)));
            }
        }
    }
}
