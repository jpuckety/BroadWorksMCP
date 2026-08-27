package co.pitayagroup.mcp.broadworks.web.portal;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@link PortalMeController} through the real portal security chain: anonymous callers get 401,
 * an {@code oidcLogin()} session returns ID-token claims, and logout returns 204 then 401.
 */
@SpringBootTest(properties = "broadworks.storage.backend=IN_MEMORY")
@AutoConfigureMockMvc
class PortalMeControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedMeReturns401() throws Exception {
        mockMvc.perform(get("/api/portal/me").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticatedMeReturnsIdTokenClaims() throws Exception {
        mockMvc.perform(get("/api/portal/me").accept(MediaType.APPLICATION_JSON)
                        .with(oidcLogin().idToken(token -> token
                                .claim("email", "alice@example.com")
                                .claim("name", "Alice Example")
                                .claim("picture", "https://example.com/alice.jpg"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("alice@example.com"))
                .andExpect(jsonPath("$.name").value("Alice Example"))
                .andExpect(jsonPath("$.picture").value("https://example.com/alice.jpg"));
    }

    @Test
    void logoutReturns204AndSubsequentMeIsUnauthorized() throws Exception {
        final MvcResult logout = mockMvc.perform(post("/api/portal/logout")
                        .with(oidcLogin().idToken(token -> token.claim("email", "alice@example.com")))
                        .with(csrf()))
                .andExpect(status().isNoContent())
                .andExpect(unauthenticated())
                .andReturn();

        final var me = get("/api/portal/me").accept(MediaType.APPLICATION_JSON);
        if (logout.getRequest().getSession(false) instanceof MockHttpSession session) {
            me.session(session);
        }
        mockMvc.perform(me).andExpect(status().isUnauthorized());
    }
}
