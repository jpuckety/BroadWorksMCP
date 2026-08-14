package co.pitayagroup.mcp.broadworks.web.portal.dto;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;

/**
 * Non-secret JSON view of a BroadWorks connection returned to the web portal. The password is
 * intentionally omitted; {@code needsPassword} is computed from a blank/absent stored password.
 */
public record ConnectionResponse(
        String resourceId,
        String displayName,
        String hostname,
        int port,
        String username,
        boolean needsPassword) {

    /**
     * @return a password-free view of the given resource, with {@code needsPassword} derived from a
     * blank stored password.
     */
    public static ConnectionResponse from(AlpacaResource resource) {
        return new ConnectionResponse(
                resource.resourceId(),
                resource.displayName(),
                resource.hostname(),
                resource.port(),
                resource.username(),
                resource.password() == null || resource.password().isBlank());
    }
}
