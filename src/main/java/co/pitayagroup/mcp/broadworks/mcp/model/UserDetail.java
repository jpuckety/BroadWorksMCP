package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Detailed view of a single BroadWorks user.
 *
 * @param userId                     the (system-unique) user id.
 * @param groupId                    the owning group id.
 * @param serviceProviderId          the owning service provider id.
 * @param firstName                  the user's first name, if any.
 * @param lastName                   the user's last name, if any.
 * @param phoneNumber                the user's phone number, if any.
 * @param extension                  the user's extension, if any.
 * @param emailAddress               the user's email address, if any.
 * @param department                 the user's department full path, if any.
 * @param title                      the user's title, if any.
 * @param mobilePhoneNumber          the user's mobile phone number, if any.
 * @param timeZone                   the user's time zone, if any.
 * @param language                   the user's language, if any.
 * @param callingLineIdFirstName     the user's calling line id first name, if any.
 * @param callingLineIdLastName      the user's calling line id last name, if any.
 * @param callingLineIdPhoneNumber   the user's calling line id phone number, if any.
 * @param address                    the physical (street) address, or {@code null} when absent.
 */
public record UserDetail(
        String userId,
        String groupId,
        String serviceProviderId,
        String firstName,
        String lastName,
        String phoneNumber,
        String extension,
        String emailAddress,
        String department,
        String title,
        String mobilePhoneNumber,
        String timeZone,
        String language,
        String callingLineIdFirstName,
        String callingLineIdLastName,
        String callingLineIdPhoneNumber,
        AddressInfo address
) {
}
