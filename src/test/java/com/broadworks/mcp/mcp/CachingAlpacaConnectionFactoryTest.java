package com.broadworks.mcp.mcp;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.inmemory.InMemoryResourceStore;
import com.broadworks.mcp.auth.store.inmemory.NoopEncryptionService;
import com.broadworks.mcp.config.AlpacaProperties;

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
        return new AlpacaResource(id, "Display", "as.example.com", 2208, "SYSTEM", "admin", "pw", false);
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
    void resolvesSingleResourceThenReportsLiveRuntimeUnavailable() {
        resourceStore.put("sub-1", resource("res-1"));

        // Per-tenant resolution and config mapping succeed; the live login is unavailable in this
        // build (requires the provisioned Alpaca runtime), surfaced as a safe message.
        assertThatThrownBy(() -> factory.connect("sub-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("not available in this build");
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
