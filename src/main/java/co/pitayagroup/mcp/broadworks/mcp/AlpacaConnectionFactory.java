package co.pitayagroup.mcp.broadworks.mcp;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;

import co.ecg.alpaca.toolkit.model.BroadWorksServer;

/**
 * Supplies a logged-in {@link BroadWorksServer} for a given tenant.
 *
 * <p>Implementations resolve the caller's {@code AlpacaResource} from the resource store (keyed by
 * {@code subject}), log in through the Alpaca toolkit, and typically cache the connection per
 * {@code (subject, resourceId)}. This is the single seam MCP tools use to reach BroadWorks, keeping
 * them free of connection concerns and easy to test.</p>
 */
public interface AlpacaConnectionFactory {

    /**
     * @param subject    the authenticated user's IdP subject (tenant key).
     * @param resourceId the id of the BroadWorks resource to use, or {@code null} to use the user's
     *                   sole/first configured resource.
     * @return a logged-in {@link BroadWorksServer}.
     * @throws AlpacaException if no resource is configured or the connection cannot be established.
     */
    BroadWorksServer connect(String subject, String resourceId);

    /**
     * Verifies that the given (possibly not-yet-stored) connection can log in to BroadWorks. Performs
     * a fresh login using the supplied credentials and immediately closes it: the attempt is never
     * served from — nor added to — the per-tenant connection cache, so it can safely be used to test
     * candidate settings from the web portal before they are saved.
     *
     * @param resource the connection to test; must carry a non-blank password.
     * @throws AlpacaException with a safe, secret-free message when the connection has no password or
     *                         the login cannot be established.
     */
    void verify(AlpacaResource resource);
}
