package co.pitayagroup.mcp.broadworks.mcp;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.config.AlpacaProperties;

import co.ecg.alpaca.toolkit.LibraryProperties;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Live {@link AlpacaConnectionFactory}: performs a real OCI login against BroadWorks using the Alpaca
 * toolkit's {@code BroadWorksServer} login machinery. This is the default runtime factory (see
 * {@link co.pitayagroup.mcp.broadworks.config.LiveAlpacaConfig}). When {@code broadworks.alpaca.live=false}
 * (tests), {@link CachingAlpacaConnectionFactory} is used instead and does not perform a live login.
 *
 * <p>Each connection is a fresh prototype {@link BroadWorksServer} bean (with its own OCS switchboard,
 * request bundler and JCS response cache) supplied by the Spring context. Resolution of the caller's
 * per-tenant {@link AlpacaResource}, license seeding and connection caching are inherited from
 * {@link CachingAlpacaConnectionFactory}; this class only overrides {@link #login(AlpacaResource)} to
 * establish the live connection.</p>
 */
@Slf4j
public class LiveAlpacaConnectionFactory extends CachingAlpacaConnectionFactory {

    private final ObjectProvider<BroadWorksServer> serverProvider;

    public LiveAlpacaConnectionFactory(ResourceStore resourceStore,
                                       AlpacaProperties alpacaProperties,
                                       ObjectProvider<BroadWorksServer> serverProvider) {
        super(resourceStore, alpacaProperties);
        this.serverProvider = serverProvider;
    }

    @Override
    protected BroadWorksServer login(AlpacaResource resource) {
        final LibraryProperties.BroadWorksServerConfig config = buildServerConfig(resource);
        // A fresh prototype BroadWorksServer; constructing it validates the ECG license already seeded
        // by applyLicense() (invoked by the superclass before login()).
        final BroadWorksServer server = serverProvider.getObject();
        try {
            // connect() opens the OCI socket and performs the login using the credentials in config.
            server.connect(config);
            log.info("Established live BroadWorks connection to host={} loginType={}",
                    resource.hostname(), server.getLoginType());
            return server;
        } catch (AlpacaException ex) {
            closeQuietly(server);
            throw ex;
        } catch (Exception ex) {
            closeQuietly(server);
            if (DnsDiagnostics.isNameResolutionFailure(ex)) {
                // The bare UnknownHostException names neither the resolver that was asked nor whether
                // the zone or the whole resolver path is at fault, which makes it undiagnosable from
                // the logs alone (in ECS the tasks have no public IP and no interactive shell).
                log.warn("BroadWorks host {} did not resolve; {}",
                        resource.hostname(), DnsDiagnostics.describe(resource.hostname()));
            }
            // No secrets are included: only the host and the toolkit's own message.
            throw new AlpacaException("Failed to establish a live BroadWorks connection to "
                    + resource.hostname() + ": " + ex.getMessage(), ex);
        }
    }

    private static void closeQuietly(BroadWorksServer server) {
        if (server == null) {
            return;
        }
        try {
            server.close();
        } catch (Exception ignore) {
            // Best-effort cleanup of a half-open connection; the original failure is propagated.
        }
    }
}
