package co.pitayagroup.mcp.broadworks.mcp.tools;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;

/**
 * Shared validation for the non-secret fields of a BroadWorks connection.
 *
 * <p>Applies the same required-field and port-range checks and, crucially, the same
 * {@link HostAllowlist} SSRF screening at both call sites — the MCP {@code broadworks_add_connection}
 * tool and the web portal — so a hostname is accepted or refused identically regardless of surface.
 * The SSRF rejection message is deliberately uniform and never reveals whether the target exists or
 * is reachable.</p>
 */
public final class ConnectionValidation {

    private ConnectionValidation() {
    }

    /**
     * Validates the non-secret connection target fields and screens the host against SSRF.
     *
     * @param hostAllowlist the SSRF screen to apply to {@code hostname}.
     * @param hostname      BroadWorks OCI hostname (no scheme or path); required.
     * @param port          BroadWorks OCI port; must be in {@code 1..65535}.
     * @param username      BroadWorks login username; required.
     * @throws AlpacaException with a safe, secret-free message when any field is invalid or the host
     *                         is not a permitted connection target.
     */
    public static void validate(HostAllowlist hostAllowlist, String hostname, int port, String username) {
        if (hostname == null || hostname.isBlank()) {
            throw new AlpacaException("hostname is required");
        }
        if (port <= 0 || port > 65535) {
            throw new AlpacaException("port must be between 1 and 65535");
        }
        if (!hostAllowlist.isAllowed(hostname)) {
            // Deliberately uniform message: never reveal whether the target exists or is reachable.
            throw new AlpacaException("hostname is not a permitted BroadWorks connection target");
        }
        if (username == null || username.isBlank()) {
            throw new AlpacaException("username is required");
        }
    }
}
