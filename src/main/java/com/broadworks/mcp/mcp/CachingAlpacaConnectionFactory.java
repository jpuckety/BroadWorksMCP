package com.broadworks.mcp.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.config.AlpacaProperties;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.model.BroadWorksLoginType;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link AlpacaConnectionFactory}: resolves the caller's {@link AlpacaResource} from the
 * {@link ResourceStore} (per-tenant, keyed by {@code subject}), builds the toolkit connection config,
 * logs in, and caches the resulting {@link BroadWorksServer} per {@code (subject, resourceId)} up to
 * the configured idle TTL.
 *
 * <p><b>Runtime note:</b> establishing a live connection uses the Alpaca toolkit's
 * {@code BroadWorksServer} login machinery, which requires the toolkit's runtime companions
 * ({@code org.apache.jcs:jcs} and {@code co.ecg:ecg-licensing}) on the classpath and a reachable
 * BroadWorks OCI server. Those companions are provisioned in the deployed environment; when they are
 * absent this factory fails fast with a safe {@link AlpacaException} (after successfully resolving
 * and validating the per-tenant resource). All per-tenant resolution, argument mapping, and response
 * handling are exercised independently of a live server.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CachingAlpacaConnectionFactory implements AlpacaConnectionFactory {

    private final ResourceStore resourceStore;
    private final AlpacaProperties alpacaProperties;

    private final ConcurrentMap<String, CachedConnection> cache = new ConcurrentHashMap<>();

    @Override
    public BroadWorksServer connect(String subject, String resourceId) {
        if (subject == null || subject.isBlank()) {
            throw new AlpacaException("No authenticated user in context");
        }
        final AlpacaResource resource = resolveResource(subject, resourceId);
        final String key = subject + "#" + resource.resourceId();

        final CachedConnection existing = cache.get(key);
        if (existing != null && existing.isFresh(alpacaProperties.connectionCacheTtl())) {
            return existing.server();
        }
        final BroadWorksServer server = login(resource);
        cache.put(key, new CachedConnection(server, Instant.now()));
        return server;
    }

    private AlpacaResource resolveResource(String subject, String resourceId) {
        if (resourceId != null && !resourceId.isBlank()) {
            return resourceStore.get(subject, resourceId)
                    .orElseThrow(() -> new AlpacaException(
                            "No BroadWorks resource '" + resourceId + "' is configured for the current user"));
        }
        final List<AlpacaResource> resources = resourceStore.listForUser(subject);
        if (resources.isEmpty()) {
            throw new AlpacaException("No BroadWorks connection is configured for the current user");
        }
        if (resources.size() > 1) {
            throw new AlpacaException("Multiple BroadWorks resources are configured; specify a resourceId");
        }
        return resources.get(0);
    }

    /**
     * Builds the toolkit connection config from the resource and performs the login. Overridable so a
     * deployment with the full Alpaca runtime can supply the live login implementation.
     */
    protected BroadWorksServer login(AlpacaResource resource) {
        // Map the per-tenant resource onto the toolkit connection config (no secrets are logged).
        final LibraryProperties.BroadWorksServerConfig config = new LibraryProperties.BroadWorksServerConfig();
        config.setHostname(resource.hostname());
        config.setPort(resource.port());
        config.setUsername(resource.username());
        config.setPassword(resource.password());
        config.setUsePrivateApplicationServerAddress(resource.usePrivateApplicationServerAddress());
        // Validate the configured login type early.
        parseLoginType(resource.loginType());

        log.info("Preparing BroadWorks connection to host={} loginType={} (login performed by the "
                + "provisioned Alpaca runtime)", resource.hostname(), resource.loginType());
        throw new AlpacaException("Live BroadWorks connectivity is not available in this build: the "
                + "Alpaca toolkit runtime companions (org.apache.jcs:jcs and co.ecg:ecg-licensing) and a "
                + "reachable BroadWorks server must be provisioned in the deployment environment");
    }

    private static BroadWorksLoginType parseLoginType(String loginType) {
        if (loginType == null || loginType.isBlank()) {
            return BroadWorksLoginType.SYSTEM;
        }
        try {
            return BroadWorksLoginType.valueOf(loginType.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AlpacaException("Unsupported BroadWorks login type: " + loginType);
        }
    }

    /** A cached connection with the instant it was established. */
    private record CachedConnection(BroadWorksServer server, Instant establishedAt) {
        boolean isFresh(Duration ttl) {
            return Optional.ofNullable(establishedAt)
                    .map(at -> at.plus(ttl).isAfter(Instant.now()))
                    .orElse(false);
        }
    }
}
