package co.pitayagroup.mcp.broadworks.mcp.model;

import java.util.List;

/**
 * A snapshot of the service authorization state at a service provider or group level.
 *
 * <p>Authorization sets are bounded (enum-sized for services, pack-count for packs) and form a
 * coherent snapshot, so they are returned as list-bearing detail records rather than a paginated
 * page.</p>
 *
 * @param userServices  the user service authorizations.
 * @param groupServices the group service authorizations.
 * @param servicePacks  the service pack authorizations (empty for a service-provider-level read).
 */
public record ServiceAuthorizationSet(
        List<ServiceAuthorization> userServices,
        List<ServiceAuthorization> groupServices,
        List<ServiceAuthorization> servicePacks
) {
}
