package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.NoopEncryptionService;
import co.pitayagroup.mcp.broadworks.config.PublicBaseUrlProperties;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;
import co.pitayagroup.mcp.broadworks.mcp.model.AddConnectionResult;
import co.pitayagroup.mcp.broadworks.mcp.model.ConnectionSummary;
import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools.ConnectionDetails;
import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools.ConnectionId;

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

@ExtendWith(MockitoExtension.class)
class ConnectionToolsTest {

    private InMemoryResourceStore resourceStore;
    private ConnectionTools tools;

    @Mock
    private McpSyncRequestContext requestContext;

    @BeforeEach
    void setUp() {
        resourceStore = new InMemoryResourceStore(new NoopEncryptionService());
        // The SSRF guard is exercised in HostAllowlistTest; these tests use fictional hostnames that
        // must not depend on DNS, so target screening is opted out of here.
        tools = new ConnectionTools(resourceStore, new HostAllowlist(true),
                new PublicBaseUrlProperties("mcp.example.com"));
        authenticateAs("sub-1", "user@example.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject, String email) {
        final var principal = new DefaultOAuth2AuthenticatedPrincipal(subject,
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, subject, UserInfo.EMAIL_ATTRIBUTE, email),
                List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    }

    @Test
    void addConnectionPersistsResourcePasswordlessAndFlagsNeedsPassword() {
        final ConnectionSummary summary = tools.addConnection(
                "ECG Production", "portal.vwave.net", 2208, "jpuckett", null, null).connection();

        assertThat(summary.resourceId()).isEqualTo("ecg-production");
        assertThat(summary.displayName()).isEqualTo("ECG Production");
        assertThat(summary.hostname()).isEqualTo("portal.vwave.net");
        assertThat(summary.port()).isEqualTo(2208);
        assertThat(summary.username()).isEqualTo("jpuckett");
        // The tool never collects a password, so the stored connection needs one set in the portal.
        assertThat(summary.needsPassword()).isTrue();

        final AlpacaResource stored = resourceStore.get("sub-1", "ecg-production").orElseThrow();
        // The password is stored as null (absent), not an empty string, so the encryption layer skips
        // it (KMS rejects zero-length plaintext).
        assertThat(stored.password()).isNull();
    }

    @Test
    void addConnectionReturnsConcretePortalUrlAndMessage() {
        final AddConnectionResult result = tools.addConnection(
                "ECG Production", "portal.vwave.net", 2208, "jpuckett", null, null);

        // The deep link points at the configured public base URL and the connection's password page.
        assertThat(result.portalUrl())
                .isEqualTo("https://mcp.example.com/portal/ecg-production/password");
        // The ready-to-relay message names the connection and embeds the concrete URL (no secret).
        assertThat(result.message())
                .contains("ECG Production")
                .contains(result.portalUrl());
    }

    @Test
    void addConnectionPortalUrlFallsBackToLocalBaseUrlWhenNoPublicHostname() {
        final ConnectionTools localTools = new ConnectionTools(resourceStore, new HostAllowlist(true),
                new PublicBaseUrlProperties(null));

        final AddConnectionResult result = localTools.addConnection(
                "Lab", "lab.example.com", 2208, "admin", "custom-id", null);

        assertThat(result.portalUrl())
                .isEqualTo("http://localhost:8080/portal/custom-id/password");
    }

    @Test
    void addConnectionHonoursExplicitResourceId() {
        final ConnectionSummary summary = tools.addConnection(
                "Lab", "lab.example.com", 2208, "admin", "custom-id", null).connection();

        assertThat(summary.resourceId()).isEqualTo("custom-id");
    }

    @Test
    void listConnectionsReturnsOnlyCurrentUserResourcesWithoutSecret() {
        tools.addConnection("Prod", "prod.example.com", 2208, "admin", null, null);
        tools.addConnection("Lab", "lab.example.com", 2208, "admin", null, null);

        final List<ConnectionSummary> connections = tools.listConnections();

        assertThat(connections).extracting(ConnectionSummary::resourceId)
                .containsExactlyInAnyOrder("prod", "lab");
        // Password-less connections are surfaced to the agent as needing a password.
        assertThat(connections).allMatch(ConnectionSummary::needsPassword);
    }

    @Test
    void deleteConnectionRemovesResource() {
        tools.addConnection("Prod", "prod.example.com", 2208, "admin", null, null);

        final String message = tools.deleteConnection("prod", null);

        assertThat(message).contains("prod");
        assertThat(resourceStore.get("sub-1", "prod")).isEmpty();
    }

    @Test
    void addConnectionValidatesRequiredFields() {
        assertThatThrownBy(() ->
                tools.addConnection("Prod", "  ", 2208, "admin", null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("hostname");

        assertThatThrownBy(() ->
                tools.addConnection("Prod", "prod.example.com", 0, "admin", null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("port");

        assertThatThrownBy(() ->
                tools.addConnection("Prod", "prod.example.com", 2208, "", null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("username");
    }

    @Test
    void addConnectionRejectsInternalTargetsWithoutLeakingReachability() {
        final ConnectionTools guarded = new ConnectionTools(resourceStore, new HostAllowlist(false));

        for (String blocked : List.of("127.0.0.1", "localhost", "169.254.169.254", "10.1.2.3",
                "192.168.0.1", "metadata.google.internal", "0.0.0.0")) {
            assertThatThrownBy(() ->
                    guarded.addConnection("Evil", blocked, 2208, "admin", null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessage("hostname is not a permitted BroadWorks connection target");
        }
        assertThat(resourceStore.listForUser("sub-1")).isEmpty();
    }

    @Test
    void failsWhenNoAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> tools.listConnections())
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No authenticated user");
    }

    @Test
    void addConnectionDoesNotElicitWhenAllFieldsPresent() {
        tools.addConnection("ECG Production", "portal.vwave.net", 2208, "jpuckett", null, requestContext);

        verify(requestContext, never()).elicitEnabled();
        verify(requestContext, never()).elicit(any(), eq(ConnectionDetails.class));
    }

    @Test
    void addConnectionUsesElicitedValuesWhenRequiredFieldsMissing() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ConnectionDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ConnectionDetails("ECG Production", "portal.vwave.net", 2208, "jpuckett"),
                        Map.of()));

        final ConnectionSummary summary = tools.addConnection(
                null, null, null, null, null, requestContext).connection();

        assertThat(summary.displayName()).isEqualTo("ECG Production");
        assertThat(summary.hostname()).isEqualTo("portal.vwave.net");
        assertThat(summary.port()).isEqualTo(2208);
        assertThat(summary.username()).isEqualTo("jpuckett");
        assertThat(summary.needsPassword()).isTrue();
    }

    @Test
    void addConnectionKeepsProvidedFieldsWhenMergingElicitation() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ConnectionDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ConnectionDetails("Elicited Name", "elicited.example.com", 2209, "elicited-user"),
                        Map.of()));

        final ConnectionSummary summary = tools.addConnection(
                "Kept Name", null, 2208, null, "kept-id", requestContext).connection();

        assertThat(summary.displayName()).isEqualTo("Kept Name");
        assertThat(summary.hostname()).isEqualTo("elicited.example.com");
        assertThat(summary.port()).isEqualTo(2208);
        assertThat(summary.username()).isEqualTo("elicited-user");
        assertThat(summary.resourceId()).isEqualTo("kept-id");
    }

    @Test
    void addConnectionFailsWhenElicitationDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ConnectionDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() ->
                tools.addConnection(null, null, null, null, null, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("Connection details were not provided");
        assertThat(resourceStore.listForUser("sub-1")).isEmpty();
    }

    @Test
    void deleteConnectionUsesElicitedResourceIdWhenMissing() {
        tools.addConnection("Prod", "prod.example.com", 2208, "admin", null, null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ConnectionId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ConnectionId("prod"), Map.of()));

        final String message = tools.deleteConnection(null, requestContext);

        assertThat(message).contains("prod");
        assertThat(resourceStore.get("sub-1", "prod")).isEmpty();
    }
}
