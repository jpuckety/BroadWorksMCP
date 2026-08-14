package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Contact information for a BroadWorks entity (e.g. a service provider or group).
 *
 * @param contactName   the contact person's name, if any.
 * @param contactNumber the contact phone number, if any.
 * @param contactEmail  the contact email address, if any.
 */
public record ContactInfo(
        String contactName,
        String contactNumber,
        String contactEmail
) {
}
