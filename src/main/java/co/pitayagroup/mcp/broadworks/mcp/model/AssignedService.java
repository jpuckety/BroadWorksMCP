package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * A single service assigned to a BroadWorks group or user.
 *
 * @param serviceName the service display name.
 * @param active      whether the service is currently active.
 */
public record AssignedService(
        String serviceName,
        boolean active
) {
}
