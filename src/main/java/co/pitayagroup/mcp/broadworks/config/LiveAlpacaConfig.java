package co.pitayagroup.mcp.broadworks.config;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.LiveAlpacaConnectionFactory;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.messaging.MessageDigestUtils;
import co.ecg.alpaca.toolkit.messaging.OCSClient;
import co.ecg.alpaca.toolkit.messaging.OCSSwitchboard;
import co.ecg.alpaca.toolkit.messaging.request.RequestBundle;
import co.ecg.alpaca.toolkit.messaging.request.RequestBundler;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import co.ecg.alpaca.toolkit.service.HostIdCachingService;
import co.ecg.alpaca.toolkit.service.LicenseService;
import co.ecg.alpaca.toolkit.service.SpringApplicationService;
import co.ecg.licensing.ECGLicense;
import org.apache.jcs.JCS;
import org.apache.jcs.access.exception.CacheException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.concurrent.CustomizableThreadFactory;

/**
 * Wires live BroadWorks connectivity. Active by default ({@code broadworks.alpaca.live=true}, env
 * {@code ALPACA_LIVE}). When the flag is explicitly {@code false} (tests), this configuration is
 * skipped and {@link AlpacaConfig} supplies a non-login {@link co.pitayagroup.mcp.broadworks.mcp.CachingAlpacaConnectionFactory}.
 *
 * <p>The Alpaca toolkit's connection objects are ordinary Spring beans ({@code @Component}s wired by
 * constructor injection). Rather than pull in the toolkit's own {@code LibraryConfig} — which is a
 * {@code @SpringBootApplication} and references {@code alpaca-server} classes that are intentionally
 * not on this classpath — this configuration registers exactly the beans the live login graph needs:
 * the prototype {@link BroadWorksServer} and its collaborators, the executors the request bundler
 * looks up by name, and a prototype {@link JCS} response cache. A fresh {@link BroadWorksServer} is
 * obtained per connection via an {@link ObjectProvider}.</p>
 *
 * <p>The response cache uses JCS defaults; a deployment can supply a tuned {@code cache.ccf} on the
 * classpath (or via {@code -Dalpaca.cache.config=/path/to/cache.ccf}) and it will be picked up
 * automatically.</p>
 *
 * <p>Parts of the toolkit do not receive their collaborators by injection but reach back into Spring
 * through the static {@link SpringApplicationService} holder, so it must be a bean here too: its
 * {@code ApplicationContextAware} callback is what populates that holder. Without it the first such
 * lookup fails with {@code Cannot invoke "ApplicationContext.getBean(Class)" because
 * "SpringApplicationService.CONTEXT" is null} — in practice right after a successful login, when
 * {@code ResponseBundleHandler} (constructed for every response bundle) resolves
 * {@link LibraryProperties} that way, surfacing as "BroadWorks Server Creation Error!".</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broadworks.alpaca", name = "live", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(LibraryProperties.class)
@Import({
        BroadWorksServer.class,
        OCSSwitchboard.class,
        OCSClient.class,
        RequestBundler.class,
        RequestBundle.class,
        MessageDigestUtils.class,
        HostIdCachingService.class,
        SpringApplicationService.class
})
public class LiveAlpacaConfig {

    /** General-purpose toolkit executor; the request bundler looks this up by the name "AlpacaExecutor". */
    @Bean(name = "AlpacaExecutor")
    public ExecutorService alpacaExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L,
                TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadFactory(new CustomizableThreadFactory("alpaca-"));
        return executor;
    }

    @Bean(name = "UserExecutor")
    public ExecutorService userExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 25, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        executor.setThreadFactory(new CustomizableThreadFactory("user-information-"));
        return executor;
    }

    @Bean(name = "DeviceExecutor")
    public ExecutorService deviceExecutor() {
        final ThreadPoolExecutor executor = new ThreadPoolExecutor(0, 25, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        executor.setThreadFactory(new CustomizableThreadFactory("device-information-"));
        return executor;
    }

    /** Per-connection JCS response cache (mirrors the toolkit's own prototype cache bean). */
    @Bean
    @Scope("prototype")
    public JCS jcsResponseCache() throws CacheException {
        // Use a fixed region name backed by the default config in cache.ccf to avoid
        // "props is null" when JCS tries to load properties for a random/undefined region name.
        return JCS.getInstance("alpacaResponseCache");
    }

    /**
     * License lookup for the toolkit's static {@code LegacyLicenseService}. With the application
     * context now available it resolves this bean instead of going straight to the licensing runtime;
     * were it missing, every call would log "Failed to get LicenseService bean" before falling back to
     * exactly what this bean returns — the shared {@link ECGLicense} singleton seeded from
     * {@code broadworks.alpaca.license-key} by the connection factory.
     */
    @Bean
    public LicenseService alpacaLicenseService() {
        return ECGLicense::getLicense;
    }

    @Bean
    public AlpacaConnectionFactory alpacaConnectionFactory(ResourceStore resourceStore,
                                                           AlpacaProperties alpacaProperties,
                                                           ObjectProvider<BroadWorksServer> serverProvider) {
        return new LiveAlpacaConnectionFactory(resourceStore, alpacaProperties, serverProvider);
    }
}
