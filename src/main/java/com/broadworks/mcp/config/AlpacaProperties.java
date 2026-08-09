package com.broadworks.mcp.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alpaca toolkit connection behaviour shared across all tenants.
 *
 * <p>Per-user host / port / credentials live in the {@code ResourceStore}; these properties only
 * control cross-cutting connection behaviour such as cache idle-eviction.</p>
 *
 * @param connectionCacheTtl how long an idle logged-in {@code BroadWorksServer} connection is kept
 *                           before it is evicted from the per-{@code (subject, resourceId)} cache.
 */
@ConfigurationProperties(prefix = "broadworks.alpaca")
public record AlpacaProperties(
        Duration connectionCacheTtl
) {
    /** Default idle lifetime of a cached Alpaca connection. */
    public static final Duration DEFAULT_CONNECTION_CACHE_TTL = Duration.ofMinutes(30);

    public AlpacaProperties {
        if (connectionCacheTtl == null) {
            connectionCacheTtl = DEFAULT_CONNECTION_CACHE_TTL;
        }
    }
}
