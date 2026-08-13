package co.pitayagroup.mcp.broadworks.mcp.tools;

/**
 * Detailed view of a single BroadWorks service provider.
 *
 * @param serviceProviderId   the service provider id.
 * @param serviceProviderName the service provider display name.
 * @param defaultDomain       the default domain.
 * @param enterprise          whether this service provider is an enterprise.
 * @param resellerId          the owning reseller id, if any.
 */
public record ServiceProviderDetail(
        String serviceProviderId,
        String serviceProviderName,
        String defaultDomain,
        boolean enterprise,
        String resellerId
) {
}
