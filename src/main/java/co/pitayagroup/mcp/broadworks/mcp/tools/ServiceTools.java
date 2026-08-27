package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;
import java.util.List;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.AssignedService;
import co.pitayagroup.mcp.broadworks.mcp.model.AssignedServicesResult;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceAuthorization;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceAuthorizationSet;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceQuantity;
import co.pitayagroup.mcp.broadworks.mcp.util.AlpacaRequests;
import co.pitayagroup.mcp.broadworks.mcp.util.ServiceEnums;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.Group;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.User;
import co.ecg.alpaca.toolkit.generated.datatypes.AssignedGroupServicesEntry;
import co.ecg.alpaca.toolkit.generated.datatypes.AssignedUserServicesEntry;
import co.ecg.alpaca.toolkit.generated.datatypes.GroupServiceAuthorization;
import co.ecg.alpaca.toolkit.generated.datatypes.ServicePackAuthorization;
import co.ecg.alpaca.toolkit.generated.datatypes.UserServiceAuthorization;
import co.ecg.alpaca.toolkit.generated.enums.GroupService;
import co.ecg.alpaca.toolkit.generated.enums.UserService;
import co.ecg.alpaca.toolkit.generated.tables.GroupServiceGroupServicesAuthorizationTableRow;
import co.ecg.alpaca.toolkit.generated.tables.GroupServiceServicePacksAuthorizationTableRow;
import co.ecg.alpaca.toolkit.generated.tables.GroupServiceUserServicesAuthorizationTableRow;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServiceGroupServicesAuthorizationTableRow;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServiceUserServicesAuthorizationTableRow;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks service authorization and service assignment/activation across the three
 * provisioning levels (service provider, group, user), backed by the Alpaca toolkit.
 *
 * <p>Operations run against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject}); results are mapped to compact DTOs. No credentials or protocol bodies are
 * logged. Mutating tools clearly state that they change live BroadWorks data.</p>
 *
 * <p>Service authorization controls how many of each user service, group service, and service pack a
 * service provider grants to itself and a group grants to itself. Modifications follow a
 * partial-update discipline: only the entries you supply are sent, and BroadWorks leaves every
 * omitted service untouched.</p>
 *
 * <p>When a client supports MCP elicitation, tools will pause and request any missing required
 * identifiers rather than failing immediately. Optional connection {@code resourceId} and
 * service/authorization lists are never elicited.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceTools {

    private final AlpacaConnectionFactory connectionFactory;

    ServiceAuthorizationSet getServiceProviderServiceAuthorization(String serviceProviderId, String resourceId) {
        return getServiceProviderServiceAuthorization(serviceProviderId, resourceId, null);
    }

    @McpTool(name = "broadworks_get_service_provider_service_authorization",
            description = "Get the service authorization for a BroadWorks service provider: how many of each "
                    + "user service and group service the service provider is authorized to consume. Each entry "
                    + "reports whether the service is authorized and, when authorized, the licensed quantity "
                    + "(a finite number or unlimited). Service packs are authorized at the group level, so the "
                    + "servicePacks list is empty here. "
                    + "If serviceProviderId is omitted and the client supports elicitation, the server will "
                    + "request it.")
    public ServiceAuthorizationSet getServiceProviderServiceAuthorization(
            @McpToolParam(required = false,
                    description = "The service provider id whose authorization to read")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String spId = require(ToolElicitation.resolveServiceProviderId(serviceProviderId, requestContext),
                "serviceProviderId");
        log.debug("tool broadworks_get_service_provider_service_authorization invoked "
                + "(serviceProviderId={}, resourceId={})", spId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        return readServiceProviderAuthorization(sp, spId);
    }

    ServiceAuthorizationSet modifyServiceProviderServiceAuthorization(String serviceProviderId,
            List<ServiceAuthorization> userServices, List<ServiceAuthorization> groupServices, String resourceId) {
        return modifyServiceProviderServiceAuthorization(
                serviceProviderId, userServices, groupServices, resourceId, null);
    }

    @McpTool(name = "broadworks_modify_service_provider_service_authorization",
            description = "Modify the service authorization for a BroadWorks service provider. This mutates live "
                    + "BroadWorks data. Supply only the user services and/or group services you want to change; "
                    + "every service you omit is left untouched (partial update). For each entry set authorized=true "
                    + "with a quantity (a positive integer, or unlimited=true) to grant, or authorized=false to "
                    + "revoke (unauthorize) the service. Service names are BroadWorks display names (e.g. "
                    + "'Call Waiting'); an unknown name is rejected. Returns the refreshed authorization snapshot. "
                    + "If serviceProviderId is omitted and the client supports elicitation, the server will "
                    + "request it.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public ServiceAuthorizationSet modifyServiceProviderServiceAuthorization(
            @McpToolParam(required = false,
                    description = "The service provider id whose authorization to change")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "User service authorization entries to change; each has a serviceName "
                            + "(BroadWorks display name), authorized (true to grant, false to revoke), and an "
                            + "optional quantity {quantity:int, unlimited:bool}. Omit services you are not changing")
            List<ServiceAuthorization> userServices,
            @McpToolParam(required = false,
                    description = "Group service authorization entries to change; same shape as userServices. "
                            + "Omit services you are not changing")
            List<ServiceAuthorization> groupServices,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String spId = require(ToolElicitation.resolveServiceProviderId(serviceProviderId, requestContext),
                "serviceProviderId");
        log.debug("tool broadworks_modify_service_provider_service_authorization invoked "
                + "(serviceProviderId={}, resourceId={})", spId, resourceId);
        final UserServiceAuthorization[] userAuths = ServiceEnums.userServiceAuthorizations(userServices);
        final GroupServiceAuthorization[] groupAuths = ServiceEnums.groupServiceAuthorizations(groupServices);
        if (userAuths.length == 0 && groupAuths.length == 0) {
            throw new AlpacaException(
                    "At least one user or group service authorization entry is required");
        }
        final BroadWorksServer server = connect(resourceId);
        final ServiceProvider sp = populatedServiceProvider(server, spId);
        try {
            final ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest request =
                    new ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest(sp);
            if (userAuths.length > 0) {
                request.setUserServiceAuthorization(userAuths);
            }
            if (groupAuths.length > 0) {
                request.setGroupServiceAuthorization(groupAuths);
            }
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response,
                    "modify service authorization for service provider " + spId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_modify_service_provider_service_authorization succeeded "
                    + "(serviceProviderId={})", spId);
            return readServiceProviderAuthorization(sp, spId);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_modify_service_provider_service_authorization failed: {}",
                    ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_modify_service_provider_service_authorization failed unexpectedly: {}",
                    ex.getMessage());
            throw new AlpacaException(
                    "Failed to modify service authorization for service provider " + spId, ex);
        }
    }

    ServiceAuthorizationSet getGroupServiceAuthorization(String serviceProviderId, String groupId, String resourceId) {
        return getGroupServiceAuthorization(serviceProviderId, groupId, resourceId, null);
    }

    @McpTool(name = "broadworks_get_group_service_authorization",
            description = "Get the service authorization for a BroadWorks group: how many of each service pack, "
                    + "group service and user service the group is authorized to consume. Each entry reports "
                    + "whether it is authorized and, when authorized, the licensed quantity (a finite number or "
                    + "unlimited). "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the server "
                    + "will request them.")
    public ServiceAuthorizationSet getGroupServiceAuthorization(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the group") String serviceProviderId,
            @McpToolParam(required = false,
                    description = "The id of the group whose authorization to read") String groupId,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef groupRef =
                ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(groupRef.serviceProviderId(), "serviceProviderId");
        final String grpId = require(groupRef.groupId(), "groupId");
        log.debug("tool broadworks_get_group_service_authorization invoked "
                + "(serviceProviderId={}, groupId={}, resourceId={})", spId, grpId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        final Group group = populatedGroup(server, spId, grpId);
        return readGroupAuthorization(group, spId, grpId);
    }

    ServiceAuthorizationSet modifyGroupServiceAuthorization(String serviceProviderId, String groupId,
            List<ServiceAuthorization> userServices, List<ServiceAuthorization> groupServices,
            List<ServiceAuthorization> servicePacks, String resourceId) {
        return modifyGroupServiceAuthorization(
                serviceProviderId, groupId, userServices, groupServices, servicePacks, resourceId, null);
    }

    @McpTool(name = "broadworks_modify_group_service_authorization",
            description = "Modify the service authorization for a BroadWorks group. This mutates live BroadWorks "
                    + "data. Supply only the service packs, group services and/or user services you want to "
                    + "change; every entry you omit is left untouched (partial update). For each entry set "
                    + "authorized=true with a quantity (a positive integer, or unlimited=true) to grant, or "
                    + "authorized=false to revoke (unauthorize). Service names are BroadWorks display names (e.g. "
                    + "'Call Waiting'); service pack names are their defined names on the service provider. An "
                    + "unknown service name is rejected. Returns the refreshed authorization snapshot. "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the server "
                    + "will request them.",
            annotations = @McpTool.McpAnnotations(destructiveHint = true))
    public ServiceAuthorizationSet modifyGroupServiceAuthorization(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the group") String serviceProviderId,
            @McpToolParam(required = false,
                    description = "The id of the group whose authorization to change") String groupId,
            @McpToolParam(required = false,
                    description = "User service authorization entries to change; each has a serviceName "
                            + "(BroadWorks display name), authorized (true to grant, false to revoke), and an "
                            + "optional quantity {quantity:int, unlimited:bool}. Omit services you are not changing")
            List<ServiceAuthorization> userServices,
            @McpToolParam(required = false,
                    description = "Group service authorization entries to change; same shape as userServices. "
                            + "Omit services you are not changing")
            List<ServiceAuthorization> groupServices,
            @McpToolParam(required = false,
                    description = "Service pack authorization entries to change; the serviceName field carries the "
                            + "service pack name. Same authorized/quantity shape as the service entries. Omit packs "
                            + "you are not changing")
            List<ServiceAuthorization> servicePacks,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef groupRef =
                ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(groupRef.serviceProviderId(), "serviceProviderId");
        final String grpId = require(groupRef.groupId(), "groupId");
        log.debug("tool broadworks_modify_group_service_authorization invoked "
                + "(serviceProviderId={}, groupId={}, resourceId={})", spId, grpId, resourceId);
        final UserServiceAuthorization[] userAuths = ServiceEnums.userServiceAuthorizations(userServices);
        final GroupServiceAuthorization[] groupAuths = ServiceEnums.groupServiceAuthorizations(groupServices);
        final ServicePackAuthorization[] packAuths = ServiceEnums.servicePackAuthorizations(servicePacks);
        if (userAuths.length == 0 && groupAuths.length == 0 && packAuths.length == 0) {
            throw new AlpacaException(
                    "At least one user service, group service or service pack authorization entry is required");
        }
        final BroadWorksServer server = connect(resourceId);
        final Group group = populatedGroup(server, spId, grpId);
        try {
            final Group.GroupServiceModifyAuthorizationListRequest request =
                    new Group.GroupServiceModifyAuthorizationListRequest(group);
            if (packAuths.length > 0) {
                request.setServicePackAuthorization(packAuths);
            }
            if (groupAuths.length > 0) {
                request.setGroupServiceAuthorization(groupAuths);
            }
            if (userAuths.length > 0) {
                request.setUserServiceAuthorization(userAuths);
            }
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response,
                    "modify service authorization for group " + spId + "/" + grpId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_modify_group_service_authorization succeeded "
                    + "(serviceProviderId={}, groupId={})", spId, grpId);
            return readGroupAuthorization(group, spId, grpId);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_modify_group_service_authorization failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_modify_group_service_authorization failed unexpectedly: {}",
                    ex.getMessage());
            throw new AlpacaException(
                    "Failed to modify service authorization for group " + spId + "/" + grpId, ex);
        }
    }

    List<String> assignGroupServices(String serviceProviderId, String groupId, List<String> serviceNames,
            String resourceId) {
        return assignGroupServices(serviceProviderId, groupId, serviceNames, resourceId, null);
    }

    @McpTool(name = "broadworks_assign_group_services",
            description = "Assign one or more group services to a BroadWorks group so the group can use them. This "
                    + "mutates live BroadWorks data. Service names are BroadWorks display names (e.g. "
                    + "'Auto Attendant'); an unknown name is rejected. The group must already be authorized for a "
                    + "service before it can be assigned. Returns the list of group service names that were assigned. "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the server "
                    + "will request them.")
    public List<String> assignGroupServices(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the group") String serviceProviderId,
            @McpToolParam(required = false,
                    description = "The id of the group to assign services to") String groupId,
            @McpToolParam(description = "Group service display names to assign (e.g. 'Auto Attendant')")
            List<String> serviceNames,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef groupRef =
                ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(groupRef.serviceProviderId(), "serviceProviderId");
        final String grpId = require(groupRef.groupId(), "groupId");
        log.debug("tool broadworks_assign_group_services invoked (serviceProviderId={}, groupId={}, resourceId={})",
                spId, grpId, resourceId);
        final GroupService[] services = requireGroupServices(serviceNames);
        final BroadWorksServer server = connect(resourceId);
        final Group group = populatedGroup(server, spId, grpId);
        try {
            final Group.GroupServiceAssignListRequest request =
                    new Group.GroupServiceAssignListRequest(group);
            request.setServiceName(services);
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "assign group services to group " + spId + "/" + grpId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_assign_group_services succeeded (serviceProviderId={}, groupId={})",
                    spId, grpId);
            return displayNames(services);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_assign_group_services failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_assign_group_services failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to assign group services to group " + spId + "/" + grpId, ex);
        }
    }

    List<String> unassignGroupServices(String serviceProviderId, String groupId, List<String> serviceNames,
            String resourceId) {
        return unassignGroupServices(serviceProviderId, groupId, serviceNames, resourceId, null);
    }

    @McpTool(name = "broadworks_unassign_group_services",
            description = "Unassign one or more group services from a BroadWorks group, removing the group's access "
                    + "to them. This mutates live BroadWorks data. Service names are BroadWorks display names (e.g. "
                    + "'Auto Attendant'); an unknown name is rejected. Returns the list of group service names that "
                    + "were unassigned. "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the server "
                    + "will request them.")
    public List<String> unassignGroupServices(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the group") String serviceProviderId,
            @McpToolParam(required = false,
                    description = "The id of the group to unassign services from") String groupId,
            @McpToolParam(description = "Group service display names to unassign (e.g. 'Auto Attendant')")
            List<String> serviceNames,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef groupRef =
                ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(groupRef.serviceProviderId(), "serviceProviderId");
        final String grpId = require(groupRef.groupId(), "groupId");
        log.debug("tool broadworks_unassign_group_services invoked (serviceProviderId={}, groupId={}, resourceId={})",
                spId, grpId, resourceId);
        final GroupService[] services = requireGroupServices(serviceNames);
        final BroadWorksServer server = connect(resourceId);
        final Group group = populatedGroup(server, spId, grpId);
        try {
            final Group.GroupServiceUnassignListRequest request =
                    new Group.GroupServiceUnassignListRequest(group);
            request.setServiceName(services);
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "unassign group services from group " + spId + "/" + grpId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_unassign_group_services succeeded (serviceProviderId={}, groupId={})",
                    spId, grpId);
            return displayNames(services);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_unassign_group_services failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_unassign_group_services failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to unassign group services from group " + spId + "/" + grpId, ex);
        }
    }

    AssignedServicesResult getUserAssignedServices(String userId, String resourceId) {
        return getUserAssignedServices(userId, resourceId, null);
    }

    @McpTool(name = "broadworks_get_user_assigned_services",
            description = "Get the services assigned to a BroadWorks user, split into the group services and user "
                    + "services granted to that user. Each entry reports the service display name and whether it is "
                    + "currently active. "
                    + "If userId is omitted and the client supports elicitation, the server will request it.")
    public AssignedServicesResult getUserAssignedServices(
            @McpToolParam(required = false,
                    description = "The id of the user whose assigned services to read") String userId,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String uId = require(ToolElicitation.resolveUserId(userId, requestContext), "userId");
        log.debug("tool broadworks_get_user_assigned_services invoked (userId={}, resourceId={})",
                uId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        final User user = populatedUser(server, uId);
        return readUserAssignedServices(server, user, uId);
    }

    AssignedServicesResult assignUserServices(String userId, List<String> serviceNames,
            List<String> servicePackNames, String resourceId) {
        return assignUserServices(userId, serviceNames, servicePackNames, resourceId, null);
    }

    @McpTool(name = "broadworks_assign_user_services",
            description = "Assign one or more user services and/or service packs to a BroadWorks user. This mutates "
                    + "live BroadWorks data. Service names are BroadWorks display names (e.g. 'Call Waiting'); an "
                    + "unknown name is rejected. Service pack names are their defined names on the service provider. "
                    + "The group must be authorized for a service or pack before it can be assigned to a user. "
                    + "Supply at least one service name or service pack name. Returns the refreshed set of assigned "
                    + "services. "
                    + "If userId is omitted and the client supports elicitation, the server will request it.")
    public AssignedServicesResult assignUserServices(
            @McpToolParam(required = false,
                    description = "The id of the user to assign services to") String userId,
            @McpToolParam(required = false,
                    description = "User service display names to assign (e.g. 'Call Waiting'); omit if only "
                            + "assigning service packs")
            List<String> serviceNames,
            @McpToolParam(required = false,
                    description = "Service pack names to assign; omit if only assigning individual user services")
            List<String> servicePackNames,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String uId = require(ToolElicitation.resolveUserId(userId, requestContext), "userId");
        log.debug("tool broadworks_assign_user_services invoked (userId={}, resourceId={})", uId, resourceId);
        final UserService[] services = ServiceEnums.userServices(serviceNames);
        final String[] packs = servicePackNames(servicePackNames);
        if (services.length == 0 && packs.length == 0) {
            throw new AlpacaException("At least one user service name or service pack name is required");
        }
        final BroadWorksServer server = connect(resourceId);
        final User user = populatedUser(server, uId);
        try {
            final User.UserServiceAssignListRequest request = new User.UserServiceAssignListRequest(user);
            if (services.length > 0) {
                request.setServiceName(services);
            }
            if (packs.length > 0) {
                request.setServicePackName(packs);
            }
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "assign services to user " + uId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_assign_user_services succeeded (userId={})", uId);
            return readUserAssignedServices(server, user, uId);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_assign_user_services failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_assign_user_services failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to assign services to user " + uId, ex);
        }
    }

    AssignedServicesResult unassignUserServices(String userId, List<String> serviceNames,
            List<String> servicePackNames, String resourceId) {
        return unassignUserServices(userId, serviceNames, servicePackNames, resourceId, null);
    }

    @McpTool(name = "broadworks_unassign_user_services",
            description = "Unassign one or more user services and/or service packs from a BroadWorks user, removing "
                    + "the user's access to them. This mutates live BroadWorks data. Service names are BroadWorks "
                    + "display names (e.g. 'Call Waiting'); an unknown name is rejected. Service pack names are their "
                    + "defined names on the service provider. Supply at least one service name or service pack name. "
                    + "Returns the refreshed set of assigned services. "
                    + "If userId is omitted and the client supports elicitation, the server will request it.")
    public AssignedServicesResult unassignUserServices(
            @McpToolParam(required = false,
                    description = "The id of the user to unassign services from") String userId,
            @McpToolParam(required = false,
                    description = "User service display names to unassign (e.g. 'Call Waiting'); omit if only "
                            + "unassigning service packs")
            List<String> serviceNames,
            @McpToolParam(required = false,
                    description = "Service pack names to unassign; omit if only unassigning individual user services")
            List<String> servicePackNames,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final String uId = require(ToolElicitation.resolveUserId(userId, requestContext), "userId");
        log.debug("tool broadworks_unassign_user_services invoked (userId={}, resourceId={})", uId, resourceId);
        final UserService[] services = ServiceEnums.userServices(serviceNames);
        final String[] packs = servicePackNames(servicePackNames);
        if (services.length == 0 && packs.length == 0) {
            throw new AlpacaException("At least one user service name or service pack name is required");
        }
        final BroadWorksServer server = connect(resourceId);
        final User user = populatedUser(server, uId);
        try {
            final User.UserServiceUnassignListRequest request = new User.UserServiceUnassignListRequest(user);
            if (services.length > 0) {
                request.setServiceName(services);
            }
            if (packs.length > 0) {
                request.setServicePackName(packs);
            }
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "unassign services from user " + uId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_unassign_user_services succeeded (userId={})", uId);
            return readUserAssignedServices(server, user, uId);
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_unassign_user_services failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_unassign_user_services failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to unassign services from user " + uId, ex);
        }
    }

    /** Fires the user assigned-services get request and maps the group + user service entry arrays. */
    private static AssignedServicesResult readUserAssignedServices(
            BroadWorksServer server, User user, String userId) {
        try {
            final User.UserAssignedServicesGetListRequest request =
                    new User.UserAssignedServicesGetListRequest(server);
            request.setUser(user);
            final User.UserAssignedServicesGetListResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "get assigned services for user " + userId);
            return new AssignedServicesResult(
                    mapGroupEntries(response.getGroupServiceEntry()),
                    mapUserEntries(response.getUserServiceEntry()));
        } catch (AlpacaException ex) {
            log.warn("user assigned-services read failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("user assigned-services read failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to read assigned services for user " + userId, ex);
        }
    }

    /** Maps assigned group service entries to compact DTOs; a {@code null} array yields an empty list. */
    private static List<AssignedService> mapGroupEntries(AssignedGroupServicesEntry[] entries) {
        if (entries == null) {
            return List.of();
        }
        return Arrays.stream(entries)
                .map(e -> new AssignedService(
                        ServiceEnums.displayName(e.getServiceName()), Boolean.TRUE.equals(e.getIsActive())))
                .toList();
    }

    /** Maps assigned user service entries to compact DTOs; a {@code null} array yields an empty list. */
    private static List<AssignedService> mapUserEntries(AssignedUserServicesEntry[] entries) {
        if (entries == null) {
            return List.of();
        }
        return Arrays.stream(entries)
                .map(e -> new AssignedService(
                        ServiceEnums.displayName(e.getServiceName()), Boolean.TRUE.equals(e.getIsActive())))
                .toList();
    }

    /** Parses and validates the supplied group service names, requiring at least one. */
    private static GroupService[] requireGroupServices(List<String> serviceNames) {
        final GroupService[] services = ServiceEnums.groupServices(serviceNames);
        if (services.length == 0) {
            throw new AlpacaException("At least one group service name is required");
        }
        return services;
    }

    /** Maps a group service array to its BroadWorks display names. */
    private static List<String> displayNames(GroupService[] services) {
        return Arrays.stream(services).map(ServiceEnums::displayName).toList();
    }

    /** Trims supplied service pack names, dropping blanks; a {@code null} list yields an empty array. */
    private static String[] servicePackNames(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new String[0];
        }
        return names.stream()
                .filter(n -> n != null && !n.isBlank())
                .map(String::trim)
                .toArray(String[]::new);
    }

    /** Fires the SP authorization get request and maps user + group service tables. */
    private static ServiceAuthorizationSet readServiceProviderAuthorization(ServiceProvider sp, String spId) {
        try {
            final ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest request =
                    new ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest(sp);
            final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response,
                    "get service authorization for service provider " + spId);

            final List<ServiceProviderServiceUserServicesAuthorizationTableRow> userRows =
                    response.getUserServicesAuthorizationTable();
            final List<ServiceAuthorization> users =
                    (userRows == null ? List.<ServiceProviderServiceUserServicesAuthorizationTableRow>of()
                            : userRows).stream()
                            .map(r -> authorization(r.getServiceName(), r.getAuthorized(), r.getLimited(),
                                    r.getQuantity()))
                            .toList();

            final List<ServiceProviderServiceGroupServicesAuthorizationTableRow> groupRows =
                    response.getGroupServicesAuthorizationTable();
            final List<ServiceAuthorization> groups =
                    (groupRows == null ? List.<ServiceProviderServiceGroupServicesAuthorizationTableRow>of()
                            : groupRows).stream()
                            .map(r -> authorization(r.getServiceName(), r.getAuthorized(), r.getLimited(),
                                    r.getQuantity()))
                            .toList();

            // Service packs are authorized at the group level; the service-provider read carries none.
            return new ServiceAuthorizationSet(users, groups, List.of());
        } catch (AlpacaException ex) {
            log.warn("service provider authorization read failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("service provider authorization read failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to read service authorization for service provider " + spId, ex);
        }
    }

    /** Fires the group authorization get request and maps service pack + group + user service tables. */
    private static ServiceAuthorizationSet readGroupAuthorization(Group group, String spId, String grpId) {
        try {
            final Group.GroupServiceGetAuthorizationListRequest request =
                    new Group.GroupServiceGetAuthorizationListRequest(group);
            final Group.GroupServiceGetAuthorizationListResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response,
                    "get service authorization for group " + spId + "/" + grpId);

            final List<GroupServiceUserServicesAuthorizationTableRow> userRows =
                    response.getUserServicesAuthorizationTable();
            final List<ServiceAuthorization> users =
                    (userRows == null ? List.<GroupServiceUserServicesAuthorizationTableRow>of()
                            : userRows).stream()
                            .map(r -> authorization(r.getServiceName(), r.getAuthorized(), r.getLimited(),
                                    r.getQuantity()))
                            .toList();

            final List<GroupServiceGroupServicesAuthorizationTableRow> groupRows =
                    response.getGroupServicesAuthorizationTable();
            final List<ServiceAuthorization> groups =
                    (groupRows == null ? List.<GroupServiceGroupServicesAuthorizationTableRow>of()
                            : groupRows).stream()
                            .map(r -> authorization(r.getServiceName(), r.getAuthorized(), r.getLimited(),
                                    r.getQuantity()))
                            .toList();

            final List<GroupServiceServicePacksAuthorizationTableRow> packRows =
                    response.getServicePacksAuthorizationTable();
            final List<ServiceAuthorization> packs =
                    (packRows == null ? List.<GroupServiceServicePacksAuthorizationTableRow>of()
                            : packRows).stream()
                            // Allocated is the group's authorized quantity (the limit set on the group).
                            // Allowed is the parent service-provider pool and must not be reported as quantity.
                            .map(r -> authorization(r.getServicePackName(), r.getAuthorized(), r.getLimited(),
                                    r.getAllocated()))
                            .toList();

            return new ServiceAuthorizationSet(users, groups, packs);
        } catch (AlpacaException ex) {
            log.warn("group authorization read failed: {}", ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("group authorization read failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException(
                    "Failed to read service authorization for group " + spId + "/" + grpId, ex);
        }
    }

    /**
     * Maps a single authorization table row (all columns are raw strings) to a {@link ServiceAuthorization}.
     * When the service is not authorized the quantity is {@code null}; otherwise it is derived from the
     * BroadWorks {@code limited} flag ({@code true} = a finite quantity, {@code false} = unlimited) and the
     * accompanying quantity column.
     */
    private static ServiceAuthorization authorization(
            String name, String authorized, String limited, String quantity) {
        final boolean auth = Boolean.parseBoolean(trimToNull(authorized));
        return new ServiceAuthorization(name, auth, auth ? quantityFrom(limited, quantity) : null);
    }

    /**
     * Derives a {@link ServiceQuantity} from the {@code limited} flag and the raw quantity string. A
     * {@code limited} value of {@code true} means the quantity is a finite count; {@code false} means it is
     * unlimited; when the flag is absent the raw quantity (if any) is treated as a finite count.
     */
    private static ServiceQuantity quantityFrom(String limited, String quantity) {
        final String limitedFlag = trimToNull(limited);
        // "false" means the allocation is unlimited; "true" (or an absent flag with a value) is finite.
        if ("false".equalsIgnoreCase(limitedFlag)) {
            return new ServiceQuantity(null, true);
        }
        final Integer count = parseIntOrNull(quantity);
        return count == null ? null : new ServiceQuantity(count, false);
    }

    private static Integer parseIntOrNull(String value) {
        final String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return Integer.valueOf(trimmed);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Resolves a populated {@link ServiceProvider}, mapping lookup failures to a clear error. */
    private static ServiceProvider populatedServiceProvider(BroadWorksServer server, String spId) {
        try {
            return ServiceProvider.getPopulatedServiceProvider(server, spId);
        } catch (BroadWorksObjectException ex) {
            throw new AlpacaException("Service provider not found or not accessible: " + spId, ex);
        }
    }

    /** Resolves a populated {@link Group}, mapping lookup failures to a clear error. */
    private static Group populatedGroup(BroadWorksServer server, String spId, String grpId) {
        try {
            return Group.getPopulatedGroup(new ServiceProvider(server, spId), grpId);
        } catch (BroadWorksObjectException ex) {
            throw new AlpacaException("Group not found or not accessible: " + spId + "/" + grpId, ex);
        }
    }

    /** Resolves a populated {@link User}, mapping lookup failures to a clear error. */
    private static User populatedUser(BroadWorksServer server, String userId) {
        try {
            return User.getPopulatedUser(server, userId);
        } catch (BroadWorksObjectException ex) {
            throw new AlpacaException("User not found or not accessible: " + userId, ex);
        }
    }

    /** Returns the trimmed value or throws when the required field is null or blank. */
    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new AlpacaException(field + " is required");
        }
        return value.trim();
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }
}
