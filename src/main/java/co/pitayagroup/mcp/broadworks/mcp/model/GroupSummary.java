package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Summary view of a BroadWorks group as returned by a list operation.
 *
 * @param groupId   the group id.
 * @param groupName the group display name.
 * @param userLimit the configured user limit (as reported by BroadWorks).
 */
public record GroupSummary(
        String groupId,
        String groupName,
        String userLimit
) {
}
