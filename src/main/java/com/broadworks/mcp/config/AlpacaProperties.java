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
 * @param licenseKey         the Alpaca toolkit license, supplied inline as a string (e.g. from the
 *                           {@code ALPACA_LICENSE_KEY} environment variable / {@code .env} file). When
 *                           set it is loaded into the ECG licensing runtime at connection time so no
 *                           on-disk license file is required. When blank, the license is expected to
 *                           be provisioned by the runtime (license file / license manager).
 */
@ConfigurationProperties(prefix = "broadworks.alpaca")
public record AlpacaProperties(
        Duration connectionCacheTtl,
        String licenseKey
) {
    /** Default idle lifetime of a cached Alpaca connection. */
    public static final Duration DEFAULT_CONNECTION_CACHE_TTL = Duration.ofMinutes(30);

    public AlpacaProperties {
        if (connectionCacheTtl == null) {
            connectionCacheTtl = DEFAULT_CONNECTION_CACHE_TTL;
        }
    }
}
