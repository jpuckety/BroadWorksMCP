package co.pitayagroup.mcp.broadworks.mcp.model;

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
 * @param needsPassword                      whether this connection still needs a password set in
 *                                           the web portal before it can be used (blank stored
 *                                           password).
 */
public record ConnectionSummary(
        String resourceId,
        String displayName,
        String hostname,
        int port,
        String loginType,
        String username,
        boolean usePrivateApplicationServerAddress,
        boolean needsPassword
) {
    /**
     * @return a non-secret summary of the given resource (password intentionally dropped). The
     * {@code needsPassword} flag is computed from a blank/absent stored password.
     */
    public static ConnectionSummary from(co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource resource) {
        return new ConnectionSummary(
                resource.resourceId(),
                resource.displayName(),
                resource.hostname(),
                resource.port(),
                resource.loginType(),
                resource.username(),
                resource.usePrivateApplicationServerAddress(),
                resource.password() == null || resource.password().isBlank());
    }
}
