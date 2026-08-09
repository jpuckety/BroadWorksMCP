package com.broadworks.mcp.mcp.tools;

/**
 * Detailed view of a single BroadWorks group.
 *
 * @param groupId           the group id.
 * @param groupName         the group display name.
 * @param serviceProviderId the owning service provider id.
 * @param defaultDomain     the group's default domain.
 */
public record GroupDetail(
        String groupId,
        String groupName,
        String serviceProviderId,
        String defaultDomain
) {
}
