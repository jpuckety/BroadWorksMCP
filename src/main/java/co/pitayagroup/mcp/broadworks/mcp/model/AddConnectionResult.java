package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Result of {@code broadworks_add_connection}: the stored connection summary plus a ready-to-relay
 * instruction that points the user at the exact web-portal page where the password is set.
 *
 * <p>The connection is stored without a password (see {@link ConnectionSummary#needsPassword()}), so
 * this result carries the concrete {@code portalUrl} — built from the server's own public base URL —
 * and a human-readable {@code message} the agent can pass straight to the end user. No secret is ever
 * included.</p>
 *
 * @param connection non-secret summary of the stored connection.
 * @param portalUrl  deep link to the web-portal page for setting this connection's password.
 * @param message    ready-to-relay instruction that includes {@code portalUrl}.
 */
public record AddConnectionResult(
        ConnectionSummary connection,
        String portalUrl,
        String message
) {
}
