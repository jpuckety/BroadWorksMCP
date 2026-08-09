package com.broadworks.mcp.auth.store;

/**
 * Per-user BroadWorks/Alpaca connection configuration and credentials.
 *
 * <p>Fields mirror the toolkit's {@code BroadWorksServerConfig} so the connection factory can map
 * them directly onto a login. The {@link #password()} is a secret: it is encrypted at rest by the
 * {@code EncryptionService} inside the {@code ResourceStore} and is never logged.</p>
 *
 * @param resourceId                       stable identifier for this resource within a user's set.
 * @param displayName                      human-friendly name / nickname.
 * @param hostname                         BroadWorks OCI host.
 * @param port                             BroadWorks OCI port.
 * @param loginType                        toolkit login type (e.g. {@code SYSTEM},
 *                                         {@code PROVISIONING}, {@code SERVICEPROVIDER}).
 * @param username                         BroadWorks login user.
 * @param password                         BroadWorks login password (secret, encrypted at rest).
 * @param usePrivateApplicationServerAddress whether to use the private AS address.
 */
public record AlpacaResource(
        String resourceId,
        String displayName,
        String hostname,
        int port,
        String loginType,
        String username,
        String password,
        boolean usePrivateApplicationServerAddress
) {
    public AlpacaResource {
        if (resourceId == null || resourceId.isBlank()) {
            throw new IllegalArgumentException("resourceId is required");
        }
    }

    /**
     * @return a copy of this resource with its password replaced (used to swap between the
     * plaintext and encrypted representations without mutating the original).
     */
    public AlpacaResource withPassword(String newPassword) {
        return new AlpacaResource(resourceId, displayName, hostname, port, loginType, username,
                newPassword, usePrivateApplicationServerAddress);
    }
}
