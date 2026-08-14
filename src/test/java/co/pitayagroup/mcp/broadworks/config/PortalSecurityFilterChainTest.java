package co.pitayagroup.mcp.broadworks.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the {@code @Order(1)} portal security chain: browser navigations to {@code /portal/**} are
 * redirected to Google login, JSON calls to {@code /api/portal/**} get a plain 401, and the portal
 * chain does not shadow the bearer-protected {@code /mcp} endpoint.
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY"
})
@AutoConfigureMockMvc
class PortalSecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedPortalPageRedirectsToGoogleLogin() throws Exception {
        mockMvc.perform(get("/portal").accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/oauth2/authorization/google"));
    }

    @Test
    void unauthenticatedPortalApiReturns401NotRedirect() throws Exception {
        mockMvc.perform(get("/api/portal/connections").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void portalChainDoesNotShadowBearerProtectedMcpEndpoint() throws Exception {
        // /mcp is handled by the app (Resource-Server) chain, which returns a bearer challenge — the
        // portal chain must not intercept it or turn it into a login redirect.
        mockMvc.perform(get("/mcp"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, containsString("Bearer")));
    }
}
