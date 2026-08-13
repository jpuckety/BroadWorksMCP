package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.NoopEncryptionService;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class ConnectionToolsTest {

    private InMemoryResourceStore resourceStore;
    private ConnectionTools tools;

    @BeforeEach
    void setUp() {
        resourceStore = new InMemoryResourceStore(new NoopEncryptionService());
        // The SSRF guard is exercised in HostAllowlistTest; these tests use fictional hostnames that
        // must not depend on DNS, so target screening is opted out of here.
        tools = new ConnectionTools(resourceStore, new HostAllowlist(true));
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
    void addConnectionPersistsResourceAndReturnsSummaryWithoutPassword() {
        final ConnectionSummary summary = tools.addConnection(
                "ECG Production", "portal.vwave.net", 2208, "jpuckett", "s3cret", null, null, null);

        assertThat(summary.resourceId()).isEqualTo("ecg-production");
        assertThat(summary.displayName()).isEqualTo("ECG Production");
        assertThat(summary.hostname()).isEqualTo("portal.vwave.net");
        assertThat(summary.port()).isEqualTo(2208);
        assertThat(summary.username()).isEqualTo("jpuckett");
        assertThat(summary.loginType()).isEqualTo("SYSTEM");
        assertThat(summary.usePrivateApplicationServerAddress()).isFalse();

        final AlpacaResource stored = resourceStore.get("sub-1", "ecg-production").orElseThrow();
        assertThat(stored.password()).isEqualTo("s3cret");
    }

    @Test
    void addConnectionHonoursExplicitResourceIdLoginTypeAndPrivateAddress() {
        final ConnectionSummary summary = tools.addConnection(
                "Lab", "lab.example.com", 2208, "admin", "pw", "provisioning", Boolean.TRUE, "custom-id");

        assertThat(summary.resourceId()).isEqualTo("custom-id");
        assertThat(summary.loginType()).isEqualTo("PROVISIONING");
        assertThat(summary.usePrivateApplicationServerAddress()).isTrue();
    }

    @Test
    void listConnectionsReturnsOnlyCurrentUserResourcesWithoutSecret() {
        tools.addConnection("Prod", "prod.example.com", 2208, "admin", "pw", null, null, null);
        tools.addConnection("Lab", "lab.example.com", 2208, "admin", "pw", null, null, null);

        final List<ConnectionSummary> connections = tools.listConnections();

        assertThat(connections).extracting(ConnectionSummary::resourceId)
                .containsExactlyInAnyOrder("prod", "lab");
    }

    @Test
    void deleteConnectionRemovesResource() {
        tools.addConnection("Prod", "prod.example.com", 2208, "admin", "pw", null, null, null);

        final String message = tools.deleteConnection("prod");

        assertThat(message).contains("prod");
        assertThat(resourceStore.get("sub-1", "prod")).isEmpty();
    }

    @Test
    void addConnectionValidatesRequiredFields() {
        assertThatThrownBy(() ->
                tools.addConnection("Prod", "  ", 2208, "admin", "pw", null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("hostname");

        assertThatThrownBy(() ->
                tools.addConnection("Prod", "prod.example.com", 0, "admin", "pw", null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("port");

        assertThatThrownBy(() ->
                tools.addConnection("Prod", "prod.example.com", 2208, "", "pw", null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("username");

        assertThatThrownBy(() ->
                tools.addConnection("Prod", "prod.example.com", 2208, "admin", "", null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("password");
    }

    @Test
    void addConnectionRejectsInternalTargetsWithoutLeakingReachability() {
        final ConnectionTools guarded = new ConnectionTools(resourceStore, new HostAllowlist(false));

        for (String blocked : List.of("127.0.0.1", "localhost", "169.254.169.254", "10.1.2.3",
                "192.168.0.1", "metadata.google.internal", "0.0.0.0")) {
            assertThatThrownBy(() ->
                    guarded.addConnection("Evil", blocked, 2208, "admin", "pw", null, null, null))
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
}
