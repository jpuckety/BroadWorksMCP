package com.broadworks.mcp.config;

import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.LiveAlpacaConnectionFactory;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.messaging.MessageDigestUtils;
import co.ecg.alpaca.toolkit.messaging.OCSClient;
import co.ecg.alpaca.toolkit.messaging.OCSSwitchboard;
import co.ecg.alpaca.toolkit.messaging.request.RequestBundle;
import co.ecg.alpaca.toolkit.messaging.request.RequestBundler;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import co.ecg.alpaca.toolkit.service.HostIdCachingService;
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
 * Wires live BroadWorks connectivity, activated only when {@code broadworks.alpaca.live=true}
 * (env {@code ALPACA_LIVE}). When the flag is absent/false this whole configuration is skipped and
 * {@link AlpacaConfig} supplies the default {@link com.broadworks.mcp.mcp.CachingAlpacaConnectionFactory}
 * (which resolves/validates a connection but does not perform a live login), so the default runtime
 * and all tests are unaffected.
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
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "broadworks.alpaca", name = "live", havingValue = "true")
@EnableConfigurationProperties(LibraryProperties.class)
@Import({
        BroadWorksServer.class,
        OCSSwitchboard.class,
        OCSClient.class,
        RequestBundler.class,
        RequestBundle.class,
        MessageDigestUtils.class,
        HostIdCachingService.class
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
        return JCS.getInstance(UUID.randomUUID().toString());
    }

    @Bean
    public AlpacaConnectionFactory alpacaConnectionFactory(ResourceStore resourceStore,
                                                           AlpacaProperties alpacaProperties,
                                                           ObjectProvider<BroadWorksServer> serverProvider) {
        return new LiveAlpacaConnectionFactory(resourceStore, alpacaProperties, serverProvider);
    }
}
