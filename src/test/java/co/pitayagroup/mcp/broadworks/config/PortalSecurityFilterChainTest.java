package co.pitayagroup.mcp.broadworks.config;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the {@code @Order(1)} portal security chain: the SPA shell at {@code /portal/**} is
 * anonymous (no Google redirect), JSON calls to {@code /api/portal/**} get a plain 401, and the
 * portal chain does not shadow the bearer-protected {@code /mcp} endpoint.
 *
 * <p>App-chain {@code defaultSuccessUrl("/portal", false)} must not override a SavedRequest to
 * {@code /oauth2/authorize}; that is covered by the existing authorize-flow tests ({@code alwaysUse}
 * is false).
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY"
})
@AutoConfigureMockMvc
class PortalSecurityFilterChainTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedPortalPageDoesNotRedirectToGoogleLogin() throws Exception {
        // SPA assets may be absent on the test classpath; assert the shell is not sent to Google.
        mockMvc.perform(get("/portal").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("/portal/index.html"));
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
