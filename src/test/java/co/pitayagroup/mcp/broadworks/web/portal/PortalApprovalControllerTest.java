package co.pitayagroup.mcp.broadworks.web.portal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.approval.ApprovalDecision;
import co.pitayagroup.mcp.broadworks.mcp.approval.InMemoryPendingApprovalStore;
import co.pitayagroup.mcp.broadworks.mcp.approval.PendingApproval;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ElicitationCompleteNotification;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MVC tests for {@link PortalApprovalController}: subject-scoped GET/POST, 404 for
 * unknown/wrong-subject/expired ids, idempotent vs conflicting decisions, and elicitation complete.
 */
@ExtendWith(MockitoExtension.class)
class PortalApprovalControllerTest {

    private static final String ACTION = "delete user 'u-1'";

    private InMemoryPendingApprovalStore store;
    private MockMvc mockMvc;

    @Mock
    private McpSyncServer mcpSyncServer;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingApprovalStore();
        @SuppressWarnings("unchecked")
        final ObjectProvider<McpSyncServer> mcpSyncServerProvider = mock(ObjectProvider.class);
        lenient().when(mcpSyncServerProvider.getIfAvailable()).thenReturn(mcpSyncServer);
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new PortalApprovalController(store, mcpSyncServerProvider))
                .setMessageConverters(new JacksonJsonHttpMessageConverter())
                .build();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void ownerGetReturnsActionAndPendingStatus() throws Exception {
        authenticateAs("sub-1");
        store.create(pending("elicit-1", "sub-1", Instant.now().plusSeconds(60)));

        mockMvc.perform(get("/api/portal/approvals/elicit-1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("elicit-1"))
                .andExpect(jsonPath("$.action").value(ACTION))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void ownerPostApprovesAndSendsElicitationComplete() throws Exception {
        authenticateAs("sub-1");
        store.create(pending("elicit-1", "sub-1", Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/api/portal/approvals/elicit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("elicit-1"))
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(store.get("elicit-1"))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.APPROVED);

        final ArgumentCaptor<ElicitationCompleteNotification> notification =
                ArgumentCaptor.forClass(ElicitationCompleteNotification.class);
        verify(mcpSyncServer).sendElicitationComplete(eq("session-1"), notification.capture());
        assertThat(notification.getValue().elicitationId()).isEqualTo("elicit-1");
    }

    @Test
    void wrongSubjectLooksLikeNotFound() throws Exception {
        authenticateAs("other");
        store.create(pending("elicit-1", "owner", Instant.now().plusSeconds(60)));

        mockMvc.perform(get("/api/portal/approvals/elicit-1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/portal/approvals/elicit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isNotFound());

        assertThat(store.get("elicit-1"))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.PENDING);
        verify(mcpSyncServer, never()).sendElicitationComplete(anyString(), any());
    }

    @Test
    void unknownAndExpiredIdsAreNotFound() throws Exception {
        authenticateAs("sub-1");
        store.create(pending("elicit-expired", "sub-1", Instant.now().minusSeconds(1)));

        mockMvc.perform(get("/api/portal/approvals/missing").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/portal/approvals/elicit-expired").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/portal/approvals/elicit-expired")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECLINED\"}"))
                .andExpect(status().isNotFound());
        verify(mcpSyncServer, never()).sendElicitationComplete(anyString(), any());
    }

    @Test
    void sameDecisionTwiceIsIdempotent() throws Exception {
        authenticateAs("sub-1");
        store.create(pending("elicit-1", "sub-1", Instant.now().plusSeconds(60)));

        mockMvc.perform(post("/api/portal/approvals/elicit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECLINED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));
        mockMvc.perform(post("/api/portal/approvals/elicit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECLINED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECLINED"));

        verify(mcpSyncServer, times(2))
                .sendElicitationComplete(eq("session-1"), any(ElicitationCompleteNotification.class));
    }

    @Test
    void conflictingSecondDecisionIsConflict() throws Exception {
        authenticateAs("sub-1");
        store.create(pending("elicit-1", "sub-1", Instant.now().plusSeconds(60)));
        store.decide("elicit-1", "sub-1", ApprovalDecision.APPROVED);

        mockMvc.perform(post("/api/portal/approvals/elicit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"DECLINED\"}"))
                .andExpect(status().isConflict());

        verify(mcpSyncServer, never()).sendElicitationComplete(anyString(), any());
    }

    @Test
    void completeNotifyFailureStillReturnsOkAfterSuccessfulWrite() throws Exception {
        authenticateAs("sub-1");
        store.create(pending("elicit-1", "sub-1", Instant.now().plusSeconds(60)));
        doThrow(new IllegalStateException("session closed"))
                .when(mcpSyncServer)
                .sendElicitationComplete(anyString(), any());

        mockMvc.perform(post("/api/portal/approvals/elicit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"APPROVED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));

        assertThat(store.get("elicit-1"))
                .get()
                .extracting(PendingApproval::decision)
                .isEqualTo(ApprovalDecision.APPROVED);
    }

    private static void authenticateAs(String subject) {
        final var principal = new DefaultOAuth2AuthenticatedPrincipal(subject,
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, subject,
                        UserInfo.EMAIL_ATTRIBUTE, subject + "@example.com"),
                List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    }

    private static PendingApproval pending(String id, String subject, Instant expiresAt) {
        return new PendingApproval(id, subject, "session-1", ACTION, ApprovalDecision.PENDING, expiresAt);
    }
}
