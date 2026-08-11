package com.broadworks.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.inmemory.InMemoryResourceStore;
import com.broadworks.mcp.auth.store.inmemory.NoopEncryptionService;
import com.broadworks.mcp.config.AlpacaProperties;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Verifies the live factory's contract without a real BroadWorks server. {@link BroadWorksServer}
 * itself is intentionally never mocked (it is {@code AutoCloseable} and cannot be instrumented here,
 * which is why the tool tests mock the request/response types instead), so the new behaviour is
 * exercised through the resource-to-config mapping, the early login-type validation, and the
 * fail-safe error path.
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
        return new AlpacaResource(id, "Display", "as.example.com", 2208, "SYSTEM", "admin", "pw", false);
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
    void rejectsInvalidLoginTypeBeforeOpeningConnection() {
        resourceStore.put("sub-1", new AlpacaResource("res-1", "Display", "as.example.com", 2208,
                "NOPE", "admin", "pw", false));

        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Unsupported BroadWorks login type");
        // Validation fails fast: no toolkit connection object is ever acquired.
        verify(serverProvider, never()).getObject();
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
