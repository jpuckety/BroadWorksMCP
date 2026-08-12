package com.broadworks.mcp.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.auth.store.inmemory.InMemoryResourceStore;
import com.broadworks.mcp.auth.store.inmemory.NoopEncryptionService;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.CachingAlpacaConnectionFactory;
import com.broadworks.mcp.mcp.LiveAlpacaConnectionFactory;

import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Verifies live vs non-live wiring toggled by {@code broadworks.alpaca.live}: live is the default;
 * tests set the flag false for the stub factory.
 */
class LiveAlpacaConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AlpacaConfig.class, LiveAlpacaConfig.class)
            .withBean(ResourceStore.class, () -> new InMemoryResourceStore(new NoopEncryptionService()))
            .withBean(AlpacaProperties.class, () -> new AlpacaProperties(null, null));

    @Test
    void defaultsToLiveFactory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AlpacaConnectionFactory.class);
            assertThat(context.getBean(AlpacaConnectionFactory.class))
                    .isInstanceOf(LiveAlpacaConnectionFactory.class);
            assertThat(context.getBeanNamesForType(BroadWorksServer.class)).isNotEmpty();
        });
    }

    @Test
    void liveTrueWiresLiveFactoryAndRegistersServerPrototype() {
        runner.withPropertyValues("broadworks.alpaca.live=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AlpacaConnectionFactory.class);
            assertThat(context.getBean(AlpacaConnectionFactory.class))
                    .isInstanceOf(LiveAlpacaConnectionFactory.class);
            // Prototype graph is registered but not instantiated (no live server contacted).
            assertThat(context.getBeanNamesForType(BroadWorksServer.class)).isNotEmpty();
        });
    }

    @Test
    void liveFalseWiresNonLiveFactory() {
        runner.withPropertyValues("broadworks.alpaca.live=false").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AlpacaConnectionFactory.class);
            assertThat(context.getBean(AlpacaConnectionFactory.class))
                    .isInstanceOf(CachingAlpacaConnectionFactory.class)
                    .isNotInstanceOf(LiveAlpacaConnectionFactory.class);
        });
    }
}
