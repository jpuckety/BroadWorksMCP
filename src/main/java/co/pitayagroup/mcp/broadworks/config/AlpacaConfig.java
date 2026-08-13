package co.pitayagroup.mcp.broadworks.config;

import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.CachingAlpacaConnectionFactory;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the non-live {@link AlpacaConnectionFactory} used only when
 * {@code broadworks.alpaca.live=false} (typically unit/integration tests). Production and local
 * runtime use {@link LiveAlpacaConfig} by default. A deployment may also contribute its own
 * {@link AlpacaConnectionFactory} bean, which this configuration defers to via
 * {@link ConditionalOnMissingBean}.
 */
@Configuration(proxyBeanMethods = false)
public class AlpacaConfig {

    @Bean
    @ConditionalOnMissingBean(AlpacaConnectionFactory.class)
    @ConditionalOnProperty(prefix = "broadworks.alpaca", name = "live", havingValue = "false")
    public AlpacaConnectionFactory alpacaConnectionFactory(ResourceStore resourceStore,
                                                           AlpacaProperties alpacaProperties) {
        return new CachingAlpacaConnectionFactory(resourceStore, alpacaProperties);
    }
}
