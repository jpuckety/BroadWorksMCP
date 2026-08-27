package co.pitayagroup.mcp.broadworks.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Verifies the end-to-end OAuth discovery -> interactive-login handoff that MCP clients rely on:
 *
 * <ol>
 *   <li>the RFC 8414 authorization-server metadata advertises the RFC 7591 dynamic client
 *       registration endpoint ({@code /oauth/register}) so clients know where to register;</li>
 *   <li>the advertised {@code issuer} is consistent with the protected-resource metadata
 *       ({@code authorization_servers}) so clients accept the discovery document;</li>
 *   <li>an unauthenticated browser hitting the authorization endpoint with a registered client is
 *       redirected to the Google login initiation endpoint.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY",
        "broadworks.oidc.client-id=test-google-client",
        "broadworks.oidc.client-secret=test-secret"
})
@AutoConfigureMockMvc
class OAuthDiscoveryAndLoginRedirectTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authorizationServerMetadataAdvertisesRegistrationEndpoint() throws Exception {
        mockMvc.perform(get("/.well-known/oauth-authorization-server").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.registration_endpoint", notNullValue()))
                .andExpect(jsonPath("$.registration_endpoint", containsString("/oauth/register")));
    }

    @Test
    void authorizationServerMetadataAdvertisesNoneTokenEndpointAuthMethod() throws Exception {
        // MCP clients register as public clients (token_endpoint_auth_method=none, PKCE), so the
        // RFC 8414 metadata must advertise "none" among the supported token-endpoint auth methods.
        mockMvc.perform(get("/.well-known/oauth-authorization-server").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_endpoint_auth_methods_supported", hasItem("none")));
    }

    @Test
    void issuerIsConsistentBetweenDiscoveryDocuments() throws Exception {
        String issuer = objectMapper
                .readTree(mockMvc.perform(get("/.well-known/oauth-authorization-server")
                        .accept(MediaType.APPLICATION_JSON))
                        .andReturn().getResponse().getContentAsString())
                .get("issuer").asText();

        // The protected-resource metadata must point clients at the same authorization server, and
        // advertise the MCP endpoint itself (<issuer>/mcp) as the protected resource -- the audience
        // the bearer token is presented at -- rather than the bare base URL.
        mockMvc.perform(get("/.well-known/oauth-protected-resource").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authorization_servers[0]", equalTo(issuer)))
                .andExpect(jsonPath("$.resource", equalTo(issuer + "/mcp")));
    }

    @Test
    void unauthenticatedBrowserAuthorizeRequestRedirectsToGoogleLogin() throws Exception {
        // Register a public client dynamically, exactly as an MCP client would.
        String clientId = registerClient("http://127.0.0.1:8123/callback");

        // A browser (Accept: text/html) hitting the authorization endpoint unauthenticated must be
        // redirected to the Google login initiation endpoint rather than receiving an error.
        mockMvc.perform(get(authorizeUrl(clientId, "http://127.0.0.1:8123/callback", null))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/google")));
    }

    @Test
    void authorizeAllowsLocalhostEphemeralPortUsedByDesktopMcpClients() throws Exception {
        // MCP Inspector / Claude Desktop / Cursor register a loopback callback then bind an
        // ephemeral port at authorize time. SAS exact-matches `localhost` (unlike 127.0.0.1),
        // which produced the production 400 on /oauth2/authorize.
        String clientId = registerClient("http://localhost:1111/callback");

        mockMvc.perform(get(authorizeUrl(clientId, "http://localhost:56056/callback",
                        "http://localhost:8080/mcp"))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/google")));
    }

    @Test
    void authorizeAllowsLocalhostWhenClientRegisteredLoopbackIp() throws Exception {
        // Claude Desktop / MCP SDK register RFC 8252's 127.0.0.1 callback, then the browser
        // authorize request uses localhost on a new ephemeral port — the production 400.
        String clientId = registerClient("http://127.0.0.1:1111/callback");

        mockMvc.perform(get(authorizeUrl(clientId, "http://localhost:56056/callback",
                        "http://localhost:8080/mcp"))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("/oauth2/authorization/google")));
    }

    @Test
    void authorizeRejectsLocalhostRedirectWithDifferentPath() throws Exception {
        String clientId = registerClient("http://localhost:1111/callback");

        mockMvc.perform(get(authorizeUrl(clientId, "http://localhost:56056/other", null))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorizeRejectsMismatchedResourceIndicator() throws Exception {
        String clientId = registerClient("http://localhost:1111/callback");

        // redirect_uri is valid, so RFC 6749 sends the error back to the client instead of a 400.
        mockMvc.perform(get(authorizeUrl(clientId, "http://localhost:56056/callback",
                        "https://evil.example.com/mcp"))
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("http://localhost:56056/callback")))
                .andExpect(header().string("Location", containsString("error=invalid_target")));
    }

    private String registerClient(String redirectUri) throws Exception {
        String regBody = "{\"redirect_uris\":[\"" + redirectUri + "\"],\"client_name\":\"Test\"}";
        MvcResult reg = mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(regBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(reg.getResponse().getContentAsString());
        return node.get("client_id").asText();
    }

    private static String authorizeUrl(String clientId, String redirectUri, String resource) {
        String url = "/oauth2/authorize?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=" + redirectUri
                + "&scope=openid"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                + "&code_challenge_method=S256";
        if (resource != null) {
            url += "&resource=" + resource;
        }
        return url;
    }

    @Test
    void googleLoginInitiationRedirectsToGoogle() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", containsString("accounts.google.com")));
    }
}
