package co.pitayagroup.mcp.broadworks.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.NoopEncryptionService;
import co.pitayagroup.mcp.broadworks.config.AlpacaProperties;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CachingAlpacaConnectionFactoryTest {

    private InMemoryResourceStore resourceStore;
    private CachingAlpacaConnectionFactory factory;

    @BeforeEach
    void setUp() {
        resourceStore = new InMemoryResourceStore(new NoopEncryptionService());
        factory = new CachingAlpacaConnectionFactory(resourceStore, new AlpacaProperties(null, null));
    }

    private AlpacaResource resource(String id) {
        return new AlpacaResource(id, "Display", "as.example.com", 2208, "admin", "pw");
    }

    private AlpacaResource passwordlessResource(String id) {
        return new AlpacaResource(id, "Display", "as.example.com", 2208, "admin", "");
    }

    @Test
    void failsCleanlyWhenNoResourceConfigured() {
        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No BroadWorks connection is configured");
    }

    @Test
    void failsCleanlyWhenNamedResourceMissing() {
        assertThatThrownBy(() -> factory.connect("sub-1", "does-not-exist"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("does-not-exist");
    }

    @Test
    void requiresResourceIdWhenMultipleConfigured() {
        resourceStore.put("sub-1", resource("res-1"));
        resourceStore.put("sub-1", resource("res-2"));

        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("specify a resourceId");
    }

    @Test
    void resolvesSingleResourceThenReportsLiveLoginDisabled() {
        resourceStore.put("sub-1", resource("res-1"));

        // Per-tenant resolution and config mapping succeed; this non-live base class never
        // opens an OCI session (runtime uses LiveAlpacaConnectionFactory instead).
        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Live BroadWorks connectivity is disabled");
    }

    @Test
    void refusesBlankPasswordAndPointsToPortal() {
        resourceStore.put("sub-1", passwordlessResource("res-1"));

        // A connection with no password yet must fail fast before any login attempt, with a
        // secret-free message that sends the user to the web portal.
        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("no password yet")
                .hasMessageContaining("web portal");
    }

    @Test
    void proceedsPastGuardWhenPasswordIsSet() {
        resourceStore.put("sub-1", resource("res-1"));

        // With a password set, the blank-password guard does not fire; this non-live base class then
        // reports that live login is disabled (i.e. it got past the guard).
        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Live BroadWorks connectivity is disabled");
    }

    @Test
    void perTenantIsolationOnResourceLookup() {
        resourceStore.put("sub-1", resource("res-1"));

        // sub-2 has no resource even though sub-1 does.
        assertThatThrownBy(() -> factory.connect("sub-2", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No BroadWorks connection is configured");
    }

    @Test
    void rejectsInvalidInlineLicenseKey() {
        // A license supplied inline as a string (e.g. from ALPACA_LICENSE_KEY) is loaded into the ECG
        // licensing runtime before the toolkit login. A malformed key is rejected up front with a
        // safe message (the GPG key ring ships inside ecg-licensing, so this needs no live server).
        final CachingAlpacaConnectionFactory licensed = new CachingAlpacaConnectionFactory(
                resourceStore, new AlpacaProperties(null, "not-a-valid-license"));
        resourceStore.put("sub-1", resource("res-1"));

        assertThatThrownBy(() -> licensed.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("license key is invalid");
    }
}
