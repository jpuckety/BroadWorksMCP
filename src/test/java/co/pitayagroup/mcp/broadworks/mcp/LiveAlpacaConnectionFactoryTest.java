package co.pitayagroup.mcp.broadworks.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.NoopEncryptionService;
import co.pitayagroup.mcp.broadworks.config.AlpacaProperties;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Verifies the live factory's contract without a real BroadWorks server. {@link BroadWorksServer}
 * itself is intentionally never mocked (it is {@code AutoCloseable} and cannot be instrumented here,
 * which is why the tool tests mock the request/response types instead), so the new behaviour is
 * exercised through the resource-to-config mapping and the fail-safe error path.
 */
class LiveAlpacaConnectionFactoryTest {

    private InMemoryResourceStore resourceStore;
    private ObjectProvider<BroadWorksServer> serverProvider;
    private LiveAlpacaConnectionFactory factory;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        resourceStore = new InMemoryResourceStore(new NoopEncryptionService());
        serverProvider = mock(ObjectProvider.class);
        factory = new LiveAlpacaConnectionFactory(resourceStore, new AlpacaProperties(null, null), serverProvider);
    }

    private AlpacaResource resource(String id) {
        return new AlpacaResource(id, "Display", "as.example.com", 2208, "admin", "pw");
    }

    @Test
    void mapsResourceOntoToolkitServerConfig() {
        final LibraryProperties.BroadWorksServerConfig config = factory.buildServerConfig(resource("res-1"));

        assertThat(config.getNickname()).isEqualTo("Display");
        assertThat(config.getHostname()).isEqualTo("as.example.com");
        assertThat(config.getPort()).isEqualTo(2208);
        assertThat(config.getUsername()).isEqualTo("admin");
        assertThat(config.getPassword()).isEqualTo("pw");
    }

    @Test
    void wrapsLoginFailureInSafeAlpacaException() {
        // Simulate the toolkit runtime being unable to supply a usable connection: the failure is
        // caught, wrapped in a safe AlpacaException (no secrets), and no half-open server leaks.
        when(serverProvider.getObject()).thenReturn(null);
        resourceStore.put("sub-1", resource("res-1"));

        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Failed to establish a live BroadWorks connection to as.example.com");
        verify(serverProvider).getObject();
    }
}
