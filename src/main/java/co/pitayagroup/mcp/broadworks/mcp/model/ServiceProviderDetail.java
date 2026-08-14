package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Detailed view of a single BroadWorks service provider.
 *
 * @param serviceProviderId   the service provider id.
 * @param serviceProviderName the service provider display name.
 * @param defaultDomain       the default domain.
 * @param enterprise          whether this service provider is an enterprise.
 * @param resellerId          the owning reseller id, if any.
 * @param supportEmail        the support email address, if any.
 * @param contact             the contact information, or {@code null} when absent.
 * @param address             the physical (street) address, or {@code null} when absent.
 */
public record ServiceProviderDetail(
        String serviceProviderId,
        String serviceProviderName,
        String defaultDomain,
        boolean enterprise,
        String resellerId,
        String supportEmail,
        ContactInfo contact,
        AddressInfo address
) {
}
