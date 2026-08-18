package co.pitayagroup.mcp.broadworks.mcp.model;

import java.util.List;

/**
 * The set of services assigned to a BroadWorks user, split by the level that grants them.
 *
 * @param groupServices the group services assigned to the user.
 * @param userServices  the user services assigned to the user.
 */
public record AssignedServicesResult(
        List<AssignedService> groupServices,
        List<AssignedService> userServices
) {
}
