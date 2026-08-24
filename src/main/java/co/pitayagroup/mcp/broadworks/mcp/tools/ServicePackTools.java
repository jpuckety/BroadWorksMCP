package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.List;
import java.util.function.Consumer;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.ServicePackDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.ServicePackSummary;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceQuantity;
import co.pitayagroup.mcp.broadworks.mcp.util.AlpacaRequests;
import co.pitayagroup.mcp.broadworks.mcp.util.ServiceEnums;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.UnboundedNonNegativeInt;
import co.ecg.alpaca.toolkit.generated.datatypes.UnboundedPositiveInt;
import co.ecg.alpaca.toolkit.generated.enums.UserService;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServicePackUserServiceTableRow;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks service pack definitions, which are named bundles of user services that
 * live on a service provider, backed by the Alpaca toolkit.
 *
 * <p>Operations run against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject}); results are mapped to compact DTOs. No credentials or protocol bodies are
 * logged. Mutating tools clearly state that they change live BroadWorks data.</p>
 *
 * <p>When a client supports MCP elicitation, list/get/create/modify/delete will pause and request
 * any missing required identifiers rather than failing immediately. Optional connection
 * {@code resourceId}, quantity, services lists, description, and availability are never elicited.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServicePackTools {

    private final AlpacaConnectionFactory connectionFactory;

    List<ServicePackSummary> listServicePacks(String serviceProviderId, String resourceId) {
        return listServicePacks(serviceProviderId, resourceId, null);
    }

    @McpTool(name = "broadworks_list_service_packs",
            description = "List the names of the service packs defined on a BroadWorks service provider. "
                    + "A service pack is a named bundle of user services that can be authorized to groups and "
                    + "assigned to users. Use broadworks_get_service_pack for the details of a single pack. "
                    + "If serviceProviderId is omitted and the client supports elicitation, the server will "
                    + "request it.")
    public List<ServicePackSummary> listServicePacks(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the service packs")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String spId = require(ToolElicitation.resolveServiceProviderId(serviceProviderId, requestContext),
                "serviceProviderId");
        log.debug("tool broadworks_list_service_packs invoked (serviceProviderId={}, resourceId={})",
                spId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        try {
            final ServiceProvider.ServiceProviderServicePackGetListRequest request =
                    new ServiceProvider.ServiceProviderServicePackGetListRequest(sp);
            final ServiceProvider.ServiceProviderServicePackGetListResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "list service packs for service provider " + spId);
            final String[] names = response.getServicePackName();
            final List<ServicePackSummary> summaries =
                    (names == null ? List.<String>of() : List.of(names)).stream()
                            .map(ServicePackSummary::new)
                            .toList();
            log.debug("tool broadworks_list_service_packs returning {} service pack(s)", summaries.size());
            return summaries;
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_list_service_packs failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_list_service_packs failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to list service packs for service provider " + spId, ex);
        }
    }

    ServicePackDetail getServicePack(String serviceProviderId, String servicePackName, String resourceId) {
        return getServicePack(serviceProviderId, servicePackName, resourceId, null);
    }

    @McpTool(name = "broadworks_get_service_pack",
            description = "Get the details of a single service pack on a BroadWorks service provider, "
                    + "including its description, availability, licensed quantity, how many are assigned/allowed, "
                    + "and the user services included in the pack. "
                    + "If serviceProviderId or servicePackName is omitted and the client supports elicitation, "
                    + "the server will request them.")
    public ServicePackDetail getServicePack(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the service pack")
            String serviceProviderId,
            @McpToolParam(required = false, description = "The name of the service pack to inspect")
            String servicePackName,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.ServicePackRef ref =
                ToolElicitation.resolveServicePackRef(serviceProviderId, servicePackName, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String packName = require(ref.servicePackName(), "servicePackName");
        log.debug("tool broadworks_get_service_pack invoked (serviceProviderId={}, servicePackName={}, "
                + "resourceId={})", spId, packName, resourceId);
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        return getDetail(sp, spId, packName);
    }

    ServicePackDetail createServicePack(String serviceProviderId, String servicePackName, String description,
            Boolean availableForUse, Integer quantity, Boolean unlimited, List<String> services, String resourceId) {
        return createServicePack(serviceProviderId, servicePackName, description, availableForUse, quantity,
                unlimited, services, resourceId, null);
    }

    @McpTool(name = "broadworks_create_service_pack",
            description = "Create a new service pack on a BroadWorks service provider. This mutates live "
                    + "BroadWorks data. servicePackName is required. The included user services are set here at "
                    + "creation time (as BroadWorks display names, e.g. 'Call Waiting') and validated against the "
                    + "known user services; an unknown name is rejected. quantity is a positive integer, or set "
                    + "unlimited=true for an unlimited allocation. availableForUse defaults to whatever BroadWorks "
                    + "applies when omitted. Fails if a pack with the same name already exists. Returns the newly "
                    + "created service pack detail. "
                    + "If serviceProviderId or servicePackName is omitted and the client supports elicitation, "
                    + "the server will request them.")
    public ServicePackDetail createServicePack(
            @McpToolParam(required = false,
                    description = "The service provider id that will own the new service pack")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "The name for the new service pack (must be unique within the service provider)")
            String servicePackName,
            @McpToolParam(required = false, description = "Optional description for the service pack")
            String description,
            @McpToolParam(required = false,
                    description = "Whether the service pack is available for assignment; omit to use the "
                            + "BroadWorks default")
            Boolean availableForUse,
            @McpToolParam(required = false,
                    description = "Licensed quantity as a positive integer; omit when unlimited=true")
            Integer quantity,
            @McpToolParam(required = false,
                    description = "Set true for an unlimited licensed quantity; when true, quantity is ignored")
            Boolean unlimited,
            @McpToolParam(required = false,
                    description = "The user services to include in the pack, as BroadWorks display names "
                            + "(e.g. 'Call Waiting', 'Third-Party Voice Mail Support'); each is validated and an "
                            + "unknown name is rejected")
            List<String> services,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.ServicePackRef ref =
                ToolElicitation.resolveServicePackRef(serviceProviderId, servicePackName, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String packName = require(ref.servicePackName(), "servicePackName");
        log.debug("tool broadworks_create_service_pack invoked (serviceProviderId={}, servicePackName={}, "
                + "resourceId={})", spId, packName, resourceId);
        final UserService[] userServices = ServiceEnums.userServices(services);
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        try {
            final ServiceProvider.ServiceProviderServicePackAddRequest request =
                    new ServiceProvider.ServiceProviderServicePackAddRequest();
            request.setServiceProvider(sp);
            request.setServicePackName(packName);
            apply(description, request::setServicePackDescription);
            if (availableForUse != null) {
                request.setIsAvailableForUse(availableForUse);
            }
            final UnboundedPositiveInt quantityValue =
                    ServiceEnums.toUnboundedPositiveInt(quantityOf(quantity, unlimited));
            if (quantityValue != null) {
                request.setServicePackQuantity(quantityValue);
            }
            if (userServices.length > 0) {
                request.setServiceName(userServices);
            }
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "create service pack " + packName);

            log.debug("tool broadworks_create_service_pack succeeded (serviceProviderId={}, servicePackName={})",
                    spId, packName);
            return getDetail(sp, spId, packName);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_create_service_pack failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_create_service_pack failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to create service pack " + packName, ex);
        }
    }

    ServicePackDetail modifyServicePack(String serviceProviderId, String servicePackName, String newServicePackName,
            String description, Boolean availableForUse, Integer quantity, Boolean unlimited, List<String> addServices,
            String resourceId) {
        return modifyServicePack(serviceProviderId, servicePackName, newServicePackName, description,
                availableForUse, quantity, unlimited, addServices, resourceId, null);
    }

    @McpTool(name = "broadworks_modify_service_pack",
            description = "Modify an existing service pack on a BroadWorks service provider. This mutates live "
                    + "BroadWorks data. Only the fields you supply are changed (partial update); omit a field to "
                    + "leave it unchanged. The in-place modifiable elements are the pack name (newServicePackName), "
                    + "description (pass an empty string to clear it), availability (availableForUse), and licensed "
                    + "quantity (quantity or unlimited=true). IMPORTANT: the included user services CANNOT be "
                    + "changed in place — BroadWorks offers no way to remove or replace a service in a pack. You may "
                    + "only ADD services via the addServices parameter (which fires a separate add-service request); "
                    + "to remove a service you must delete and recreate the pack. Returns the refreshed service pack "
                    + "detail. "
                    + "If serviceProviderId or servicePackName is omitted and the client supports elicitation, "
                    + "the server will request them.")
    public ServicePackDetail modifyServicePack(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the service pack")
            String serviceProviderId,
            @McpToolParam(required = false, description = "The current name of the service pack to modify")
            String servicePackName,
            @McpToolParam(required = false,
                    description = "New name for the service pack; omit to leave unchanged (cannot be cleared)")
            String newServicePackName,
            @McpToolParam(required = false,
                    description = "New description; omit to leave unchanged, pass an empty string to clear")
            String description,
            @McpToolParam(required = false,
                    description = "Whether the service pack is available for assignment; omit to leave unchanged")
            Boolean availableForUse,
            @McpToolParam(required = false,
                    description = "New licensed quantity as a positive integer; omit to leave unchanged. Ignored "
                            + "when unlimited=true")
            Integer quantity,
            @McpToolParam(required = false,
                    description = "Set true to make the licensed quantity unlimited; omit to leave unchanged")
            Boolean unlimited,
            @McpToolParam(required = false,
                    description = "User services to ADD to the pack, as BroadWorks display names (e.g. "
                            + "'Call Waiting'). Add-only: services cannot be removed or replaced here — to remove a "
                            + "service, delete and recreate the pack. Each name is validated and an unknown name is "
                            + "rejected")
            List<String> addServices,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.ServicePackRef ref =
                ToolElicitation.resolveServicePackRef(serviceProviderId, servicePackName, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String packName = require(ref.servicePackName(), "servicePackName");
        log.debug("tool broadworks_modify_service_pack invoked (serviceProviderId={}, servicePackName={}, "
                + "resourceId={})", spId, packName, resourceId);
        final UserService[] servicesToAdd = ServiceEnums.userServices(addServices);
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        try {
            final ServiceProvider.ServiceProviderServicePackModifyRequest request =
                    new ServiceProvider.ServiceProviderServicePackModifyRequest(sp, packName);
            if (isPresent(newServicePackName)) {
                request.setNewServicePackName(newServicePackName.trim());
            }
            apply(description, request::setServicePackDescription);
            if (availableForUse != null) {
                request.setIsAvailableForUse(availableForUse);
            }
            final UnboundedPositiveInt quantityValue =
                    ServiceEnums.toUnboundedPositiveInt(quantityOf(quantity, unlimited));
            if (quantityValue != null) {
                request.setServicePackQuantity(quantityValue);
            }
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "modify service pack " + packName);

            // Included user services cannot be modified in place; adding services (if requested) is a
            // separate OCI request. The toolkit exposes no remove/replace path — removal requires
            // deleting and recreating the pack.
            if (servicesToAdd.length > 0) {
                final ServiceProvider.ServiceProviderServicePackAddServiceListRequest addRequest =
                        new ServiceProvider.ServiceProviderServicePackAddServiceListRequest(sp, packName, servicesToAdd);
                final DefaultResponse addResponse = addRequest.fire();
                AlpacaRequests.ensureSuccess(addResponse, "add services to service pack " + packName);
            }

            // The name may have changed; read back under the effective (possibly new) name.
            final String effectiveName = isPresent(newServicePackName) ? newServicePackName.trim() : packName;
            log.debug("tool broadworks_modify_service_pack succeeded (serviceProviderId={}, servicePackName={})",
                    spId, effectiveName);
            return getDetail(sp, spId, effectiveName);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_modify_service_pack failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_modify_service_pack failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to modify service pack " + packName, ex);
        }
    }

    String deleteServicePack(String serviceProviderId, String servicePackName, String resourceId) {
        return deleteServicePack(serviceProviderId, servicePackName, resourceId, null);
    }

    @McpTool(name = "broadworks_delete_service_pack",
            description = "Delete a service pack from a BroadWorks service provider. This mutates live "
                    + "BroadWorks data and is irreversible. BroadWorks may reject the deletion if the pack is still "
                    + "authorized to groups or assigned to users. Returns a short confirmation message. "
                    + "If serviceProviderId or servicePackName is omitted and the client supports elicitation, "
                    + "the server will request them.")
    public String deleteServicePack(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the service pack")
            String serviceProviderId,
            @McpToolParam(required = false, description = "The name of the service pack to delete")
            String servicePackName,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.ServicePackRef ref =
                ToolElicitation.resolveServicePackRef(serviceProviderId, servicePackName, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String packName = require(ref.servicePackName(), "servicePackName");
        log.debug("tool broadworks_delete_service_pack invoked (serviceProviderId={}, servicePackName={}, "
                + "resourceId={})", spId, packName, resourceId);
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        try {
            final ServiceProvider.ServiceProviderServicePackDeleteRequest request =
                    new ServiceProvider.ServiceProviderServicePackDeleteRequest(sp, packName);
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "delete service pack " + packName);
            log.debug("tool broadworks_delete_service_pack succeeded (serviceProviderId={}, servicePackName={})",
                    spId, packName);
            return "Deleted service pack '" + packName + "' from service provider " + spId;
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_delete_service_pack failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_delete_service_pack failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to delete service pack " + packName, ex);
        }
    }

    /** Fires a detail-list request for a single pack and maps it to a {@link ServicePackDetail}. */
    private static ServicePackDetail getDetail(ServiceProvider sp, String spId, String packName) {
        try {
            final ServiceProvider.ServiceProviderServicePackGetDetailListRequest request =
                    new ServiceProvider.ServiceProviderServicePackGetDetailListRequest(sp, packName);
            final ServiceProvider.ServiceProviderServicePackGetDetailListResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response,
                    "get service pack " + packName + " for service provider " + spId);
            return toDetail(response);
        } catch (AlpacaException ex) {
            log.warn("tool service pack detail lookup failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool service pack detail lookup failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to read service pack " + packName, ex);
        }
    }

    /** Maps a detail-list response to a compact {@link ServicePackDetail} DTO. */
    private static ServicePackDetail toDetail(
            ServiceProvider.ServiceProviderServicePackGetDetailListResponse response) {
        final List<ServiceProviderServicePackUserServiceTableRow> rows = response.getUserServiceTable();
        final List<String> userServices =
                (rows == null ? List.<ServiceProviderServicePackUserServiceTableRow>of() : rows).stream()
                        .map(ServiceProviderServicePackUserServiceTableRow::getService)
                        .toList();
        final UnboundedNonNegativeInt assigned = response.getAssignedQuantity();
        return new ServicePackDetail(
                response.getServicePackName(),
                response.getServicePackDescription(),
                response.getIsAvailableForUse(),
                ServiceEnums.toQuantity(response.getServicePackQuantity()),
                assigned == null ? null : assigned.getQuantity(),
                ServiceEnums.toQuantity(response.getAllowedQuantity()),
                userServices);
    }

    /** Resolves a populated {@link ServiceProvider}, mapping lookup failures to a clear error. */
    private static ServiceProvider populatedServiceProvider(BroadWorksServer server, String spId) {
        try {
            return ServiceProvider.getPopulatedServiceProvider(server, spId);
        } catch (BroadWorksObjectException ex) {
            throw new AlpacaException("Service provider not found or not accessible: " + spId, ex);
        }
    }

    /**
     * Builds a {@link ServiceQuantity} from the tool's integer/unlimited parameters, or {@code null}
     * when neither is supplied (leave unchanged / use the BroadWorks default).
     */
    private static ServiceQuantity quantityOf(Integer quantity, Boolean unlimited) {
        if (quantity == null && !Boolean.TRUE.equals(unlimited)) {
            return null;
        }
        return new ServiceQuantity(quantity, Boolean.TRUE.equals(unlimited));
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /** Returns the trimmed value or throws when the required field is null or blank. */
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AlpacaException(field + " is required");
        }
        return value.trim();
    }

    /**
     * Applies a tool-supplied string using set/clear/leave semantics against an Alpaca setter: a
     * {@code null} value leaves the field unchanged (the setter is never called), a blank value clears
     * it by passing {@code null} to the setter, and any other value sets the trimmed string.
     */
    private static void apply(String value, Consumer<String> setter) {
        if (value == null) {
            return;
        }
        setter.accept(value.isBlank() ? null : value.trim());
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }
}
