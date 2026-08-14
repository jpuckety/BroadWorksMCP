package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Detailed view of a single BroadWorks group.
 *
 * @param groupId                   the group id.
 * @param groupName                 the group display name.
 * @param serviceProviderId         the owning service provider id.
 * @param defaultDomain             the group's default domain.
 * @param userCount                 the current number of users in the group, if known.
 * @param userLimit                 the maximum number of users allowed in the group, if known.
 * @param callingLineIdName         the group calling line id name, if any.
 * @param callingLineIdPhoneNumber  the group calling line id phone number, if any.
 * @param timeZone                  the group time zone, if any.
 * @param locationDialingCode       the group location dialing code, if any.
 * @param contact                   the contact information, or {@code null} when absent.
 * @param address                   the physical (street) address, or {@code null} when absent.
 */
public record GroupDetail(
        String groupId,
        String groupName,
        String serviceProviderId,
        String defaultDomain,
        Integer userCount,
        Integer userLimit,
        String callingLineIdName,
        String callingLineIdPhoneNumber,
        String timeZone,
        String locationDialingCode,
        ContactInfo contact,
        AddressInfo address
) {
}
