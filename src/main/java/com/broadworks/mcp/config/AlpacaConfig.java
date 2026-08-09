package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.CachingAlpacaConnectionFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the {@link AlpacaConnectionFactory}. A deployment with the full Alpaca runtime can supply an
 * alternative bean (which this configuration backs off from).
 */
@Configuration(proxyBeanMethods = false)
public class AlpacaConfig {

    @Bean
    @ConditionalOnMissingBean(AlpacaConnectionFactory.class)
    public AlpacaConnectionFactory alpacaConnectionFactory(ResourceStore resourceStore,
                                                           AlpacaProperties alpacaProperties) {
        return new CachingAlpacaConnectionFactory(resourceStore, alpacaProperties);
    }
}
