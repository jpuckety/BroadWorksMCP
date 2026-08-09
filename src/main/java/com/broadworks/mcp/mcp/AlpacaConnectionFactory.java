package com.broadworks.mcp.mcp;

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
}
