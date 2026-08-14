package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Physical (street) address of a BroadWorks entity (e.g. a service provider or group).
 *
 * @param addressLine1     the first address line, if any.
 * @param addressLine2     the second address line, if any.
 * @param city             the city, if any.
 * @param stateOrProvince  the state or province, if any.
 * @param zipOrPostalCode  the ZIP or postal code, if any.
 * @param country          the country, if any.
 */
public record AddressInfo(
        String addressLine1,
        String addressLine2,
        String city,
        String stateOrProvince,
        String zipOrPostalCode,
        String country
) {
}
