package co.pitayagroup.mcp.broadworks.mcp.model;

import java.util.List;

/**
 * Detailed view of a service pack defined on a BroadWorks service provider.
 *
 * @param servicePackName the service pack name.
 * @param description      the service pack description, if any.
 * @param availableForUse  whether the pack is available for assignment, if reported.
 * @param quantity         the licensed quantity (finite or unlimited), if reported.
 * @param assignedQuantity the number currently assigned, if reported.
 * @param allowedQuantity  the maximum quantity that may be assigned, if reported.
 * @param userServices     the display names of the user services included in the pack.
 */
public record ServicePackDetail(
        String servicePackName,
        String description,
        Boolean availableForUse,
        ServiceQuantity quantity,
        Integer assignedQuantity,
        ServiceQuantity allowedQuantity,
        List<String> userServices
) {
}
