package co.pitayagroup.mcp.broadworks.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.util.ArrayList;

import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.NoopEncryptionService;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.CachingAlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.LiveAlpacaConnectionFactory;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.messaging.response.ResponseBundleHandler;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import co.ecg.alpaca.toolkit.service.LicenseService;
import co.ecg.alpaca.toolkit.service.SpringApplicationService;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * The toolkit's context holder is static, so a context started by another test can leave it
     * populated; clear it to observe what a fresh JVM (i.e. the deployed server) sees.
     */
    @BeforeEach
    void clearToolkitApplicationContext() throws Exception {
        final Field context = SpringApplicationService.class.getDeclaredField("CONTEXT");
        context.setAccessible(true);
        context.set(null, null);
    }

    /**
     * Reproduces "BroadWorks Server Creation Error!. Failed to connect to <host> - Cannot invoke
     * ApplicationContext.getBean(Class) because SpringApplicationService.CONTEXT is null", which hit
     * every connection just after a successful login: the toolkit builds a {@link ResponseBundleHandler}
     * for each response bundle and that constructor resolves {@link LibraryProperties} through the
     * static holder.
     */
    @Test
    void publishesTheApplicationContextToTheToolkitsStaticHolder() {
        assertThat(SpringApplicationService.hasApplicationContext()).isFalse();
        assertThatThrownBy(() -> new ResponseBundleHandler(null, new ArrayList<>()))
                .isInstanceOf(NullPointerException.class);

        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(SpringApplicationService.class);
            assertThat(SpringApplicationService.hasApplicationContext()).isTrue();
            // What the toolkit itself asks for on every response bundle.
            assertThat(SpringApplicationService.getBean(LibraryProperties.class)).isNotNull();
            assertThat(new ResponseBundleHandler(null, new ArrayList<>())).isNotNull();
        });
    }

    /** Without this bean the toolkit logs an error per license check before falling back. */
    @Test
    void suppliesTheLicenseServiceTheToolkitLooksUpOnceTheContextIsAvailable() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(LicenseService.class);
            assertThat(SpringApplicationService.getBean(LicenseService.class).getLicense()).isNotNull();
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
