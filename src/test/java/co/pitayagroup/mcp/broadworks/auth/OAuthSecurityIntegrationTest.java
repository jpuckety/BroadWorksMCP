package co.pitayagroup.mcp.broadworks.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Boots the full application (in-memory stores) and verifies the OAuth discovery/registration
 * surface and Resource-Server bearer enforcement. No Google or BroadWorks network calls are made.
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY"
        // No broadworks.public.hostname: the base URL defaults to http://localhost:8080.
})
@AutoConfigureMockMvc
class OAuthSecurityIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SessionStore sessionStore;

    @Test
    void authorizationServerMetadataIsPublished() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer", notNullValue()))
                .andExpect(jsonPath("$.authorization_endpoint", containsString("/oauth2/authorize")))
                .andExpect(jsonPath("$.token_endpoint", containsString("/oauth2/token")));
    }

    @Test
    void protectedResourceMetadataIsPublishedWithAndWithoutTrailingSlash() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource", containsString("localhost")))
                .andExpect(jsonPath("$.authorization_servers[0]", containsString("localhost")));

        mockMvc.perform(get("/.well-known/oauth-protected-resource/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource", containsString("localhost")));
    }

    @Test
    void dynamicClientRegistrationCreatesPublicClient() throws Exception {
        final String body = """
                {"redirect_uris":["http://127.0.0.1:8123/callback"],"client_name":"Test Client"}""";

        mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id", notNullValue()))
                .andExpect(jsonPath("$.token_endpoint_auth_method").value("none"))
                .andExpect(jsonPath("$.client_secret").doesNotExist());
    }

    @Test
    void dynamicClientRegistrationRejectsDisallowedHttpsRedirect() throws Exception {
        final String body = """
                {"redirect_uris":["https://evil.example.com/cb"]}""";

        mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest())
                // RFC 7591 section 3.2.2 error object, so the client can report why it failed.
                .andExpect(jsonPath("$.error").value("invalid_redirect_uri"))
                .andExpect(jsonPath("$.error_description", containsString("https://evil.example.com/cb")));
    }

    @Test
    void dynamicClientRegistrationAcceptsWellKnownClientCallback() throws Exception {
        final String body = """
                {"redirect_uris":["https://claude.ai/api/mcp/auth_callback"],"client_name":"Claude"}""";

        mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id", notNullValue()));
    }

    @Test
    void corsPreflightIsAnsweredForBrowserHostedClients() throws Exception {
        mockMvc.perform(options("/mcp")
                        .header(HttpHeaders.ORIGIN, "https://claude.ai")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://claude.ai"));

        // The token endpoint lives on the Authorization Server chain and needs the same handling.
        mockMvc.perform(options("/oauth2/token")
                        .header(HttpHeaders.ORIGIN, "https://claude.ai")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "https://claude.ai"));
    }

    @Test
    void bearerChallengeIsReadableByBrowserClientsAndUnknownOriginsAreNotEchoed() throws Exception {
        mockMvc.perform(get("/mcp").header(HttpHeaders.ORIGIN, "https://claude.ai"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        containsString(HttpHeaders.WWW_AUTHENTICATE)));

        mockMvc.perform(get("/mcp").header(HttpHeaders.ORIGIN, "https://evil.example.com"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void unauthenticatedMcpCallReturns401Challenge() throws Exception {
        mockMvc.perform(get("/whoami"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("Bearer realm=\"mcp\"")))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        containsString("resource_metadata=")));
    }

    @Test
    void validBearerTokenResolvesUserInfo() throws Exception {
        sessionStore.createSession(new Session(null, "tok-valid", null, "client-1", "sub-1",
                "user@example.com", null, null,
                Instant.now().plus(1, ChronoUnit.HOURS), null, Instant.now(),
                "authz-1", "http://localhost:8080/mcp"));

        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer tok-valid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("sub-1"))
                .andExpect(jsonPath("$.email").value("user@example.com"));
    }

    @Test
    void expiredBearerTokenIsRejected() throws Exception {
        sessionStore.createSession(new Session(null, "tok-expired", null, "client-1", "sub-2",
                "user2@example.com", null, null,
                Instant.now().minus(1, ChronoUnit.HOURS), null, Instant.now(),
                "authz-2", "http://localhost:8080/mcp"));

        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer tok-expired"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void unknownBearerTokenIsRejected() throws Exception {
        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokenWithWrongAudienceIsRejected() throws Exception {
        sessionStore.createSession(new Session(null, "tok-wrong-aud", null, "client-1", "sub-3",
                "user3@example.com", null, null,
                Instant.now().plus(1, ChronoUnit.HOURS), null, Instant.now(),
                "authz-3", "https://evil.example.com/mcp"));

        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer tok-wrong-aud"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void bearerTokenWithMissingAudienceIsRejected() throws Exception {
        sessionStore.createSession(new Session(null, "tok-no-aud", null, "client-1", "sub-4",
                "user4@example.com", null, null,
                Instant.now().plus(1, ChronoUnit.HOURS), null, Instant.now(),
                "authz-4", null));

        mockMvc.perform(get("/whoami").header(HttpHeaders.AUTHORIZATION, "Bearer tok-no-aud"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dynamicClientRegistrationAcceptsCustomScheme() throws Exception {
        final String body = """
                {"redirect_uris":["cursor://auth/callback"],"client_name":"Cursor"}""";

        mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.client_id", notNullValue()));
    }
}
