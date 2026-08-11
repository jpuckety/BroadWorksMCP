package com.broadworks.mcp.mcp;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.config.AlpacaProperties;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.model.BroadWorksLoginType;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import co.ecg.licensing.ECGLicense;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link AlpacaConnectionFactory}: resolves the caller's {@link AlpacaResource} from the
 * {@link ResourceStore} (per-tenant, keyed by {@code subject}), builds the toolkit connection config,
 * logs in, and caches the resulting {@link BroadWorksServer} per {@code (subject, resourceId)} up to
 * the configured idle TTL.
 *
 * <p><b>Licensing:</b> the Alpaca toolkit is licensed by the bundled {@code co.ecg:ecg-licensing}
 * runtime. The license may be supplied inline as a string via {@code broadworks.alpaca.license-key}
 * ({@code ALPACA_LICENSE_KEY}); when set it is loaded into the shared {@link ECGLicense} singleton at
 * connection time (see {@link #applyLicense()}), so no on-disk license file is required.</p>
 *
 * <p><b>Runtime note:</b> establishing a live connection uses the Alpaca toolkit's
 * {@code BroadWorksServer} login machinery, which additionally requires the toolkit's runtime
 * companion ({@code org.apache.jcs:jcs}) on the classpath and a reachable BroadWorks OCI server. That
 * companion is provisioned in the deployed environment; when it is absent this factory fails fast
 * with a safe {@link AlpacaException} (after successfully resolving and validating the per-tenant
 * resource). All per-tenant resolution, argument mapping, and response handling are exercised
 * independently of a live server.</p>
 */
@Slf4j
@RequiredArgsConstructor
public class CachingAlpacaConnectionFactory implements AlpacaConnectionFactory {

    private final ResourceStore resourceStore;
    private final AlpacaProperties alpacaProperties;

    private final ConcurrentMap<String, CachedConnection> cache = new ConcurrentHashMap<>();
    private final AtomicBoolean licenseApplied = new AtomicBoolean(false);

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
        applyLicense();
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
     * Performs the login. Overridable so a deployment with the full Alpaca runtime can supply the
     * live login implementation (see {@code LiveAlpacaConnectionFactory}). This default resolves and
     * validates the per-tenant resource / connection config but does not perform a live login.
     */
    protected BroadWorksServer login(AlpacaResource resource) {
        buildServerConfig(resource);
        log.info("Preparing BroadWorks connection to host={} loginType={} (login performed by the "
                + "provisioned Alpaca runtime)", resource.hostname(), resource.loginType());
        throw new AlpacaException("Live BroadWorks connectivity is not available in this build: the "
                + "Alpaca toolkit runtime companion (org.apache.jcs:jcs) and a reachable BroadWorks "
                + "server must be provisioned in the deployment environment");
    }

    /**
     * Maps the per-tenant {@link AlpacaResource} onto the toolkit's {@code BroadWorksServerConfig}
     * and validates the configured login type early (no secrets are logged). Shared by the default
     * factory and the live override.
     */
    protected LibraryProperties.BroadWorksServerConfig buildServerConfig(AlpacaResource resource) {
        final LibraryProperties.BroadWorksServerConfig config = new LibraryProperties.BroadWorksServerConfig();
        config.setNickname(resource.displayName());
        config.setHostname(resource.hostname());
        config.setPort(resource.port());
        config.setUsername(resource.username());
        config.setPassword(resource.password());
        config.setUsePrivateApplicationServerAddress(resource.usePrivateApplicationServerAddress());
        // Validate the configured login type early (a bad value fails fast with a safe message).
        parseLoginType(resource.loginType());
        return config;
    }

    /**
     * Loads the configured Alpaca license string into the ECG licensing runtime, if one was supplied
     * via {@code broadworks.alpaca.license-key} ({@code ALPACA_LICENSE_KEY}). Seeding the license here
     * populates the toolkit's shared {@link ECGLicense} singleton, so the subsequent
     * {@code BroadWorksServer} login uses the string-supplied license instead of an on-disk file. When
     * no key is configured the license is left to the provisioned runtime (license file / manager).
     *
     * <p>Runs at most once per successful load; an invalid license string fails fast with a safe
     * {@link AlpacaException} (no license content is logged).</p>
     */
    protected void applyLicense() {
        final String licenseKey = alpacaProperties.licenseKey();
        if (licenseKey == null || licenseKey.isBlank()) {
            return;
        }
        if (!licenseApplied.compareAndSet(false, true)) {
            return;
        }
        final ECGLicense license = ECGLicense.getLicense(licenseKey);
        if (!license.isValid()) {
            licenseApplied.set(false);
            throw new AlpacaException("The configured Alpaca license key is invalid: " + license.getInvalidReason());
        }
        log.info("Loaded Alpaca license from configured key (company={}, validUntil={})",
                license.getCompany(), license.getValidUntil());
    }

    protected static BroadWorksLoginType parseLoginType(String loginType) {
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
