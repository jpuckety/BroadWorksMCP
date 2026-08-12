package com.broadworks.mcp.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Verifies the CSRF scope of {@code appSecurityFilterChain}: bearer-token-only, non-browser endpoints
 * are exempt, while the session-backed paths are protected (a POST without a token is rejected with
 * 403 before authentication is attempted).
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY"
})
@AutoConfigureMockMvc
class CsrfScopeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void mcpTransportsAreCsrfExempt() throws Exception {
        // 401 (not 403): the request reaches the Resource Server, which challenges for a bearer token.
        mockMvc.perform(post("/mcp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/sse"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void dynamicClientRegistrationIsCsrfExempt() throws Exception {
        mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"redirect_uris\":[\"http://127.0.0.1:8123/callback\"]}"))
                .andExpect(status().isCreated());
    }

    @Test
    void otherPostsStillRequireACsrfToken() throws Exception {
        mockMvc.perform(post("/whoami"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/whoami").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}
