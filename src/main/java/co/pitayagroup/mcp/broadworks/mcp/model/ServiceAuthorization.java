package co.pitayagroup.mcp.broadworks.mcp.model;

/**
 * Authorization state for a single BroadWorks service or service pack at a service provider or
 * group level.
 *
 * @param serviceName the service (or service pack) display name.
 * @param authorized  whether the service is authorized ({@code false} means explicitly unauthorized).
 * @param quantity    the authorized quantity (finite or unlimited), or {@code null} when unauthorized
 *                    or unspecified.
 */
public record ServiceAuthorization(
        String serviceName,
        boolean authorized,
        ServiceQuantity quantity
) {
}
