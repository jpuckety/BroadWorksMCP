package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Summary view of a BroadWorks user as returned by a list operation.
 *
 * @param userId            the (system-unique) user id.
 * @param groupId           the owning group id.
 * @param serviceProviderId the owning service provider id.
 * @param lastName          the user's last name, if any.
 * @param firstName         the user's first name, if any.
 * @param phoneNumber       the user's phone number, if any.
 * @param extension         the user's extension, if any.
 * @param emailAddress      the user's email address, if any.
 */
public record UserSummary(
        String userId,
        String groupId,
        String serviceProviderId,
        String lastName,
        String firstName,
        String phoneNumber,
        String extension,
        String emailAddress
) {
}
