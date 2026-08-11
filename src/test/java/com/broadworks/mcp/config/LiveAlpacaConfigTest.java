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
 * Verifies the opt-in wiring toggled by {@code broadworks.alpaca.live}: the correct connection
 * factory is selected and — crucially — that enabling live mode boots a valid context (the toolkit's
 * prototype {@link BroadWorksServer} graph is registered but not instantiated, so no live server is
 * needed).
 */
class LiveAlpacaConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AlpacaConfig.class, LiveAlpacaConfig.class)
            .withBean(ResourceStore.class, () -> new InMemoryResourceStore(new NoopEncryptionService()))
            .withBean(AlpacaProperties.class, () -> new AlpacaProperties(null, null));

    @Test
    void defaultsToNonLiveFactory() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AlpacaConnectionFactory.class);
            assertThat(context.getBean(AlpacaConnectionFactory.class))
                    .isInstanceOf(CachingAlpacaConnectionFactory.class)
                    .isNotInstanceOf(LiveAlpacaConnectionFactory.class);
        });
    }

    @Test
    void enablingLiveWiresLiveFactoryAndRegistersServerPrototype() {
        runner.withPropertyValues("broadworks.alpaca.live=true").run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(AlpacaConnectionFactory.class);
            assertThat(context.getBean(AlpacaConnectionFactory.class))
                    .isInstanceOf(LiveAlpacaConnectionFactory.class);
            // The prototype BroadWorksServer bean graph is registered (but not instantiated: no live
            // server is contacted just by starting the context).
            assertThat(context.getBeanNamesForType(BroadWorksServer.class)).isNotEmpty();
        });
    }
}
