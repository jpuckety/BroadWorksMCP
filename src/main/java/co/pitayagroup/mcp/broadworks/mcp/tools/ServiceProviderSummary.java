package co.pitayagroup.mcp.broadworks.mcp.tools;

/**
 * Summary view of a BroadWorks service provider as returned by a list operation.
 *
 * @param serviceProviderId   the service provider id.
 * @param serviceProviderName the service provider display name.
 * @param enterprise          whether this service provider is an enterprise.
 * @param resellerId          the owning reseller id, if any.
 */
public record ServiceProviderSummary(
        String serviceProviderId,
        String serviceProviderName,
        boolean enterprise,
        String resellerId
) {
}
