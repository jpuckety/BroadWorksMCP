package co.pitayagroup.mcp.broadworks.web.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.OidcLoginRequestPostProcessor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Integration tests for {@link PortalConnectionController} exercised through the real portal security
 * chain: authenticated JSON CRUD scoped to the caller's subject, tenant isolation, validation/SSRF
 * rejection, and the guarantee that passwords never appear in responses.
 */
@SpringBootTest(properties = "broadworks.storage.backend=IN_MEMORY")
@AutoConfigureMockMvc
class PortalConnectionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ResourceStore resourceStore;

    @MockitoBean
    private HostAllowlist hostAllowlist;

    @BeforeEach
    void resetState() {
        when(hostAllowlist.isAllowed(anyString())).thenReturn(true);
        clear("sub-1");
        clear("sub-2");
    }

    private void clear(String subject) {
        resourceStore.listForUser(subject).forEach(r -> resourceStore.delete(subject, r.resourceId()));
    }

    private OidcLoginRequestPostProcessor loginAs(String subject) {
        return oidcLogin().idToken(token -> token.subject(subject).claim("email", subject + "@example.com"));
    }

    private AlpacaResource resource(String id, String password) {
        return new AlpacaResource(id, "Display", "as.example.com", 2208, "admin", password);
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(get("/api/portal/connections").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void createStoresConnectionWithPasswordButNeverReturnsIt() throws Exception {
        final String body = "{\"displayName\":\"ECG Prod\",\"hostname\":\"portal.example.com\",\"port\":2208,"
                + "\"username\":\"admin\",\"password\":\"s3cret\"}";

        mockMvc.perform(post("/api/portal/connections").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.resourceId").value("ecg-prod"))
                .andExpect(jsonPath("$.needsPassword").value(false))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(content().string(Matchers.not(Matchers.containsString("s3cret"))));

        assertThat(resourceStore.get("sub-1", "ecg-prod").orElseThrow().password()).isEqualTo("s3cret");
    }

    @Test
    void createWithoutPasswordFlagsNeedsPassword() throws Exception {
        final String body = "{\"displayName\":\"No Secret\",\"hostname\":\"portal.example.com\","
                + "\"port\":2208,\"username\":\"admin\"}";

        mockMvc.perform(post("/api/portal/connections").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.needsPassword").value(true));

        assertThat(resourceStore.get("sub-1", "no-secret").orElseThrow().password()).isEmpty();
    }

    @Test
    void listReturnsOnlyCurrentUsersConnections() throws Exception {
        resourceStore.put("sub-1", resource("mine", "pw"));
        resourceStore.put("sub-2", resource("theirs", "pw"));

        mockMvc.perform(get("/api/portal/connections").with(loginAs("sub-1")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].resourceId").value("mine"))
                .andExpect(content().string(Matchers.not(Matchers.containsString("pw"))));
    }

    @Test
    void updateLeavesPasswordUnchangedWhenBlank() throws Exception {
        resourceStore.put("sub-1", resource("mine", "original"));
        final String body = "{\"displayName\":\"Renamed\",\"hostname\":\"new.example.com\",\"port\":2209,"
                + "\"username\":\"admin\",\"password\":\"\"}";

        mockMvc.perform(put("/api/portal/connections/mine").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Renamed"))
                .andExpect(jsonPath("$.hostname").value("new.example.com"))
                .andExpect(jsonPath("$.needsPassword").value(false));

        final AlpacaResource stored = resourceStore.get("sub-1", "mine").orElseThrow();
        assertThat(stored.password()).isEqualTo("original");
        assertThat(stored.port()).isEqualTo(2209);
    }

    @Test
    void setPasswordUpdatesSecretAndClearsNeedsPassword() throws Exception {
        resourceStore.put("sub-1", resource("mine", ""));

        mockMvc.perform(put("/api/portal/connections/mine/password").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"newpw\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.needsPassword").value(false))
                .andExpect(jsonPath("$.password").doesNotExist());

        assertThat(resourceStore.get("sub-1", "mine").orElseThrow().password()).isEqualTo("newpw");
    }

    @Test
    void setBlankPasswordIsRejected() throws Exception {
        resourceStore.put("sub-1", resource("mine", "pw"));

        mockMvc.perform(put("/api/portal/connections/mine/password").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"password\":\"\"}"))
                .andExpect(status().isBadRequest());

        // The existing secret is untouched.
        assertThat(resourceStore.get("sub-1", "mine").orElseThrow().password()).isEqualTo("pw");
    }

    @Test
    void deleteRemovesConnection() throws Exception {
        resourceStore.put("sub-1", resource("mine", "pw"));

        mockMvc.perform(delete("/api/portal/connections/mine").with(loginAs("sub-1")).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(resourceStore.get("sub-1", "mine")).isEmpty();
    }

    @Test
    void cannotReadOrMutateAnotherUsersConnection() throws Exception {
        resourceStore.put("sub-2", resource("theirs", "pw"));
        final String body = "{\"displayName\":\"Hijack\",\"hostname\":\"evil.example.com\","
                + "\"port\":2208,\"username\":\"admin\"}";

        mockMvc.perform(get("/api/portal/connections/theirs").with(loginAs("sub-1")).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(put("/api/portal/connections/theirs").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/portal/connections/theirs").with(loginAs("sub-1")).with(csrf()))
                .andExpect(status().isNoContent());

        // sub-2's connection is untouched by sub-1's delete.
        assertThat(resourceStore.get("sub-2", "theirs")).isPresent();
    }

    @Test
    void invalidInputIsRejectedAndNotPersisted() throws Exception {
        final String blankHost = "{\"displayName\":\"Bad\",\"hostname\":\"  \",\"port\":2208,\"username\":\"admin\"}";
        mockMvc.perform(post("/api/portal/connections").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(blankHost))
                .andExpect(status().isBadRequest());

        final String badPort = "{\"displayName\":\"Bad\",\"hostname\":\"portal.example.com\",\"port\":0,\"username\":\"admin\"}";
        mockMvc.perform(post("/api/portal/connections").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(badPort))
                .andExpect(status().isBadRequest());

        assertThat(resourceStore.listForUser("sub-1")).isEmpty();
    }

    @Test
    void ssrfBlockedHostIsRejectedWithUniformMessage() throws Exception {
        when(hostAllowlist.isAllowed("10.0.0.1")).thenReturn(false);
        final String body = "{\"displayName\":\"Evil\",\"hostname\":\"10.0.0.1\",\"port\":2208,\"username\":\"admin\"}";

        mockMvc.perform(post("/api/portal/connections").with(loginAs("sub-1")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("hostname is not a permitted BroadWorks connection target"));

        assertThat(resourceStore.listForUser("sub-1")).isEmpty();
    }
}
