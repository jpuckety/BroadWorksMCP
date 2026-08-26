package co.pitayagroup.mcp.broadworks.mcp.approval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.config.PublicBaseUrlProperties;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities.Elicitation;
import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import io.modelcontextprotocol.spec.McpSchema.ElicitUrlRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    private static final String ACTION = "delete user 'u-1'";

    private InMemoryPendingApprovalStore store;
    private ConfirmationService service;

    @Mock
    private McpSyncRequestContext requestContext;

    @BeforeEach
    void setUp() {
        store = new InMemoryPendingApprovalStore();
        service = new ConfirmationService(store, new PublicBaseUrlProperties(""));
        authenticateAs("sub-1", "user@example.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void urlCapabilityElicitsEvenWhenAreYouSureTrueAndProceedsOnlyWhenStoreApproved() {
        stubUrlCapability();
        when(requestContext.sessionId()).thenReturn("mcp-session-1");
        when(requestContext.elicit(any(ElicitRequest.class))).thenAnswer(invocation -> {
            final ElicitUrlRequest request = invocation.getArgument(0);
            store.decide(request.elicitationId(), "sub-1", ApprovalDecision.APPROVED);
            return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of(), Map.of());
        });

        service.requireAreYouSure(true, ACTION, requestContext);

        final ArgumentCaptor<ElicitRequest> captor = ArgumentCaptor.forClass(ElicitRequest.class);
        verify(requestContext).elicit(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(ElicitUrlRequest.class);
        final ElicitUrlRequest urlRequest = (ElicitUrlRequest) captor.getValue();
        assertThat(urlRequest.mode()).isEqualTo(ElicitUrlRequest.MODE);
        assertThat(urlRequest.url())
                .isEqualTo("http://localhost:8080/portal/approvals/" + urlRequest.elicitationId());
        assertThat(urlRequest.message()).contains(ACTION);
        assertThat(store.get(urlRequest.elicitationId())).isEmpty();
    }

    @Test
    void urlCapabilityClientAcceptWithoutPortalClickIsRefused() {
        stubUrlCapability();
        when(requestContext.sessionId()).thenReturn("mcp-session-1");
        when(requestContext.elicit(any(ElicitRequest.class)))
                .thenReturn(new ElicitResult(ElicitResult.Action.ACCEPT, Map.of(), Map.of()));

        assertThatThrownBy(() -> service.requireAreYouSure(true, ACTION, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining(ACTION)
                .hasMessageContaining("No changes were made");
        verify(requestContext).elicit(any(ElicitRequest.class));
    }

    @Test
    void urlCapabilityDeclinedInStoreIsRefused() {
        stubUrlCapability();
        when(requestContext.sessionId()).thenReturn("mcp-session-1");
        when(requestContext.elicit(any(ElicitRequest.class))).thenAnswer(invocation -> {
            final ElicitUrlRequest request = invocation.getArgument(0);
            store.decide(request.elicitationId(), "sub-1", ApprovalDecision.DECLINED);
            return new ElicitResult(ElicitResult.Action.ACCEPT, Map.of(), Map.of());
        });

        assertThatThrownBy(() -> service.requireAreYouSure(null, ACTION, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("not approved");
    }

    @Test
    void fallbackProceedsWhenAreYouSureTrueAndNeverElicits() {
        when(requestContext.clientCapabilities()).thenReturn(formOnlyCapabilities());

        service.requireAreYouSure(true, ACTION, requestContext);

        verify(requestContext, never()).elicit(any(ElicitRequest.class));
        assertThat(store.get("anything")).isEmpty();
    }

    @Test
    void fallbackWithoutFlagNamesActionAndUrlCapableClient() {
        assertThatThrownBy(() -> service.requireAreYouSure(null, ACTION, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining(ACTION)
                .hasMessageContaining("URL-capable")
                .hasMessageContaining("areYouSure=true")
                .hasMessageContaining("No changes were made");
        assertThatThrownBy(() -> service.requireAreYouSure(false, ACTION, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining(ACTION);
        verify(requestContext, never()).elicit(any(ElicitRequest.class));
    }

    @Test
    void urlCapabilityWithoutUserContextDoesNotCreateApproval() {
        SecurityContextHolder.clearContext();
        stubUrlCapability();

        assertThatThrownBy(() -> service.requireAreYouSure(true, ACTION, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("authenticated user")
                .hasMessageContaining("No changes were made");
        verify(requestContext, never()).elicit(any(ElicitRequest.class));
        verify(requestContext, never()).sessionId();
    }

    private void stubUrlCapability() {
        when(requestContext.clientCapabilities()).thenReturn(urlCapabilities());
    }

    private static ClientCapabilities urlCapabilities() {
        return new ClientCapabilities(null, null, null,
                new Elicitation(null, new Elicitation.Url()));
    }

    private static ClientCapabilities formOnlyCapabilities() {
        return new ClientCapabilities(null, null, null,
                new Elicitation(new Elicitation.Form(), null));
    }

    private static void authenticateAs(String subject, String email) {
        final var principal = new DefaultOAuth2AuthenticatedPrincipal(subject,
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, subject, UserInfo.EMAIL_ATTRIBUTE, email),
                java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", java.util.List.of()));
    }
}
