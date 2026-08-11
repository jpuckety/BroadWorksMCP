package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.CachingAlpacaConnectionFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the default {@link AlpacaConnectionFactory} (no live login). When live connectivity is
 * enabled via {@code broadworks.alpaca.live=true} this bean backs off and {@link LiveAlpacaConfig}
 * supplies a live factory instead; a deployment may also contribute its own
 * {@link AlpacaConnectionFactory} bean, which this configuration likewise defers to.
 */
@Configuration(proxyBeanMethods = false)
public class AlpacaConfig {

    @Bean
    @ConditionalOnMissingBean(AlpacaConnectionFactory.class)
    @ConditionalOnProperty(prefix = "broadworks.alpaca", name = "live", havingValue = "false",
            matchIfMissing = true)
    public AlpacaConnectionFactory alpacaConnectionFactory(ResourceStore resourceStore,
                                                           AlpacaProperties alpacaProperties) {
        return new CachingAlpacaConnectionFactory(resourceStore, alpacaProperties);
    }
}
