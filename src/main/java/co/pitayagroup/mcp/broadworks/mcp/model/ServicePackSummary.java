package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Summary view of a service pack defined on a BroadWorks service provider, as returned by a list
 * operation.
 *
 * @param servicePackName the service pack name.
 */
public record ServicePackSummary(
        String servicePackName
) {
}
