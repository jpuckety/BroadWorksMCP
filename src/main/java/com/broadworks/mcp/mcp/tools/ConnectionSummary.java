package com.broadworks.mcp.mcp.tools;

/**
 * Non-secret summary of a configured BroadWorks/Alpaca connection.
 *
 * <p>Deliberately omits the password so connection details can be returned to MCP clients without
 * ever exposing the secret.</p>
 *
 * @param resourceId                         stable identifier for this connection within the user's set.
 * @param displayName                        human-friendly name / nickname.
 * @param hostname                           BroadWorks OCI host.
 * @param port                               BroadWorks OCI port.
 * @param loginType                          toolkit login type (e.g. {@code SYSTEM}).
 * @param username                           BroadWorks login user.
 * @param usePrivateApplicationServerAddress whether to use the private AS address.
 */
public record ConnectionSummary(
        String resourceId,
        String displayName,
        String hostname,
        int port,
        String loginType,
        String username,
        boolean usePrivateApplicationServerAddress
) {
    /**
     * @return a non-secret summary of the given resource (password intentionally dropped).
     */
    static ConnectionSummary from(com.broadworks.mcp.auth.store.AlpacaResource resource) {
        return new ConnectionSummary(
                resource.resourceId(),
                resource.displayName(),
                resource.hostname(),
                resource.port(),
                resource.loginType(),
                resource.username(),
                resource.usePrivateApplicationServerAddress());
    }
}
