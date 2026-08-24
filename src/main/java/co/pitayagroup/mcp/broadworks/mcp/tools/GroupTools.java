package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.GroupDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.GroupSummary;
import co.pitayagroup.mcp.broadworks.mcp.model.Page;
import co.pitayagroup.mcp.broadworks.mcp.util.AlpacaRequests;
import co.pitayagroup.mcp.broadworks.mcp.util.ContactAddressMapper;
import co.pitayagroup.mcp.broadworks.mcp.util.Paging;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.Group;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaGroupName;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import co.ecg.alpaca.toolkit.generated.enums.SearchMode;
import co.ecg.alpaca.toolkit.generated.tables.GroupGroupTable1Row;
import co.ecg.alpaca.toolkit.generated.tables.GroupGroupTable2Row;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks groups (within a service provider or system-wide), backed by the Alpaca toolkit.
 *
 * <p>Operations run against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject}); results are mapped to compact DTOs. No credentials or protocol bodies are
 * logged.</p>
 *
 * <p>When a client supports MCP elicitation, get/modify/create/delete will pause and request any
 * missing required identifiers or create fields rather than failing immediately. Optional filters,
 * pagination, connection {@code resourceId}, contact/address fields, and the delete
 * {@code areYouSure} flag are never elicited.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupTools {

    /** Columns emitted for each group row, in positional order. */
    static final List<String> GROUP_SCHEMA = List.of("groupId", "groupName", "userLimit");

    private final AlpacaConnectionFactory connectionFactory;

    @McpTool(name = "broadworks_list_groups",
            description = "List (or search) BroadWorks groups. When a service provider id is supplied the search "
                    + "is scoped to that service provider; when it is omitted the search spans the entire system "
                    + "(all service providers). Pass an optional search value to filter by group name. Results "
                    + "are paginated and capped server-side (max " + Paging.MAX_PAGE_LIMIT + " per page): pass the "
                    + "returned next_cursor to fetch the next page and inspect has_more/total_matching to know "
                    + "when to stop. Rows are returned in a compact columnar form described by the schema field.")
    public Page listGroups(
            @McpToolParam(required = false,
                    description = "The service provider id whose groups to list. Omit to search groups across "
                            + "the entire system (all service providers)")
            String serviceProviderId,
            @McpToolParam(required = false,
                    description = "Optional case-insensitive filter matched against the group name; omit to "
                            + "list all")
            String search,
            @McpToolParam(required = false,
                    description = "How the search value is matched: STARTSWITH, CONTAINS, or EQUALTO "
                            + "(default CONTAINS). Ignored when search is omitted")
            String searchMode,
            @McpToolParam(required = false,
                    description = "Opaque pagination cursor returned as next_cursor by a previous call; "
                            + "omit to start from the first page")
            String cursor,
            @McpToolParam(required = false,
                    description = "Maximum rows to return in this page. Clamped to the server ceiling of "
                            + Paging.MAX_PAGE_LIMIT + "; defaults to " + Paging.DEFAULT_PAGE_LIMIT + " when omitted")
            Integer limit,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_list_groups invoked (serviceProviderId={}, search={}, searchMode={}, cursor={}, "
                        + "limit={}, resourceId={})", serviceProviderId, search, searchMode, cursor, limit, resourceId);
        final int offset = Paging.decodeCursor(cursor);
        final int pageLimit = Paging.effectivePageLimit(limit, GROUP_SCHEMA.size());
        final SearchMode mode = AlpacaRequests.searchMode(searchMode);
        final boolean hasSearch = search != null && !search.isBlank();
        final boolean systemWide = serviceProviderId == null || serviceProviderId.isBlank();
        final BroadWorksServer server = connect(resourceId);
        try {
            final List<GroupSummary> summaries = systemWide
                    ? searchGroupsInSystem(server, hasSearch, mode, search)
                    : searchGroupsInServiceProvider(server, serviceProviderId, hasSearch, mode, search);
            final Page page = toPage(summaries, offset, pageLimit);
            log.debug("tool broadworks_list_groups returning {} of {} group(s) (serviceProviderId={}, systemWide={}, "
                            + "hasMore={})", page.returned(), page.totalMatching(), serviceProviderId, systemWide,
                    page.hasMore());
            return page;
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_list_groups failed (serviceProviderId={}): {}",
                    serviceProviderId, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_list_groups failed unexpectedly (serviceProviderId={}): {}",
                    serviceProviderId, ex.getMessage());
            throw new AlpacaException(systemWide
                    ? "Failed to list groups in system"
                    : "Failed to list groups in service provider " + serviceProviderId, ex);
        }
    }

    /**
     * Searches the groups within a single service provider via {@code GroupGetListInServiceProviderRequest},
     * optionally filtering by group name.
     */
    private static List<GroupSummary> searchGroupsInServiceProvider(
            BroadWorksServer server, String serviceProviderId, boolean hasSearch, SearchMode mode, String search) {
        final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
        final Group.GroupGetListInServiceProviderRequest request =
                new Group.GroupGetListInServiceProviderRequest(serviceProvider);
        if (hasSearch) {
            request.setSearchCriteriaGroupName(new SearchCriteriaGroupName(mode, search.trim(), true));
        }
        final Group.GroupGetListInServiceProviderResponse response = request.fire();
        AlpacaRequests.ensureSuccess(response, "list groups");
        final List<GroupGroupTable1Row> groupTable = response.getGroupTable();
        return (groupTable == null ? List.<GroupGroupTable1Row>of() : groupTable)
                .stream()
                .map(row -> new GroupSummary(row.getGroupId(), row.getGroupName(), row.getUserLimit()))
                .toList();
    }

    /**
     * Searches groups across the entire system (all service providers) via {@code GroupGetListInSystemRequest},
     * optionally filtering by group name.
     */
    private static List<GroupSummary> searchGroupsInSystem(
            BroadWorksServer server, boolean hasSearch, SearchMode mode, String search) {
        final Group.GroupGetListInSystemRequest request = new Group.GroupGetListInSystemRequest(server);
        if (hasSearch) {
            request.setSearchCriteriaGroupName(new SearchCriteriaGroupName(mode, search.trim(), true));
        }
        final Group.GroupGetListInSystemResponse response = request.fire();
        AlpacaRequests.ensureSuccess(response, "list groups in system");
        final List<GroupGroupTable2Row> groupTable = response.getGroupTable();
        return (groupTable == null ? List.<GroupGroupTable2Row>of() : groupTable)
                .stream()
                .map(row -> new GroupSummary(row.getGroupId(), row.getGroupName(), row.getUserLimit()))
                .toList();
    }

    /**
     * Maps the group summaries to compact columnar rows and delegates to {@link Paging#toPage} to build
     * a server-capped {@link Page} with pagination metadata.
     */
    static Page toPage(List<GroupSummary> summaries, int offset, int pageLimit) {
        final List<List<Object>> rows = summaries.stream()
                .map(g -> Arrays.<Object>asList(g.groupId(), g.groupName(), g.userLimit()))
                .toList();
        return Paging.toPage(GROUP_SCHEMA, rows, offset, pageLimit, "broadworks_list_groups", "groups");
    }

    GroupDetail getGroup(String serviceProviderId, String groupId, String resourceId) {
        return getGroup(serviceProviderId, groupId, resourceId, null, null);
    }

    public GroupDetail getGroup(String serviceProviderId, String groupId, String resourceId,
            McpSyncRequestContext requestContext) {
        return getGroup(serviceProviderId, groupId, resourceId, null, requestContext);
    }

    @McpTool(name = "broadworks_get_group",
            description = "Get details for a single BroadWorks group within a service provider, including its "
                    + "user count/limit, calling line id (name/number), time zone, location dialing code, "
                    + "contact (name/number/email), and physical address. "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the server will "
                    + "request them. Pass refresh=true to flush the OCI response cache first and fetch live data.")
    public GroupDetail getGroup(
            @McpToolParam(required = false, description = "The service provider id") String serviceProviderId,
            @McpToolParam(required = false, description = "The group id") String groupId,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            @McpToolParam(required = false,
                    description = "When true, flush the Alpaca OCI response cache before reading so the "
                            + "result is fetched live from BroadWorks rather than served from cache")
            Boolean refresh,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef ref = ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String grpId = require(ref.groupId(), "groupId");
        log.debug("tool broadworks_get_group invoked (serviceProviderId={}, groupId={}, resourceId={}, refresh={})",
                spId, grpId, resourceId, refresh);
        final BroadWorksServer server = connect(resourceId);
        AlpacaRequests.refreshIfRequested(server, refresh);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, spId);
            final Group group = Group.getPopulatedGroup(serviceProvider, grpId);
            return toDetail(group);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_group failed for serviceProviderId={} groupId={}: {}",
                    spId, grpId, ex.getMessage());
            throw new AlpacaException("Group not found or not accessible: " + spId + "/" + grpId, ex);
        }
    }

    GroupDetail modifyGroup(String serviceProviderId, String groupId, String groupName,
            String defaultDomain, Integer userLimit, String callingLineIdName,
            String callingLineIdPhoneNumber, String timeZone, String locationDialingCode,
            String contactName, String contactNumber, String contactEmail, String addressLine1,
            String addressLine2, String city, String stateOrProvince, String zipOrPostalCode,
            String country, String resourceId) {
        return modifyGroup(serviceProviderId, groupId, groupName, defaultDomain, userLimit,
                callingLineIdName, callingLineIdPhoneNumber, timeZone, locationDialingCode,
                contactName, contactNumber, contactEmail, addressLine1, addressLine2, city,
                stateOrProvince, zipOrPostalCode, country, resourceId, null);
    }

    @McpTool(name = "broadworks_modify_group",
            description = "Modify a single BroadWorks group within a service provider. This mutates live "
                    + "BroadWorks data. Only the fields you supply are changed (partial update); omit a field "
                    + "to leave it unchanged. Do NOT send placeholder values such as 'N/A' or '00000' for "
                    + "fields you are not changing — omit them entirely, otherwise BroadWorks may reject "
                    + "the request as invalid. For the clearable fields (callingLineIdName, "
                    + "callingLineIdPhoneNumber, locationDialingCode and each contact/address field) pass an "
                    + "empty string to clear the current value. groupName, defaultDomain, timeZone and userLimit "
                    + "cannot be cleared and are only changed when a value is supplied. "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the server will "
                    + "request them. Returns the refreshed group detail reflecting the applied state.")
    public GroupDetail modifyGroup(
            @McpToolParam(required = false, description = "The service provider id owning the group")
            String serviceProviderId,
            @McpToolParam(required = false, description = "The id of the group to modify") String groupId,
            @McpToolParam(required = false,
                    description = "New display name; omit to leave unchanged (cannot be cleared)")
            String groupName,
            @McpToolParam(required = false,
                    description = "New default domain; omit to leave unchanged (cannot be cleared)")
            String defaultDomain,
            @McpToolParam(required = false,
                    description = "New user limit; omit to leave unchanged (cannot be cleared)")
            Integer userLimit,
            @McpToolParam(required = false,
                    description = "Calling line id name; omit to leave unchanged, pass an empty string to clear")
            String callingLineIdName,
            @McpToolParam(required = false,
                    description = "Calling line id phone number; omit to leave unchanged, pass an empty string "
                            + "to clear")
            String callingLineIdPhoneNumber,
            @McpToolParam(required = false,
                    description = "Time zone (e.g. 'America/New_York'); omit to leave unchanged (cannot be "
                            + "cleared)")
            String timeZone,
            @McpToolParam(required = false,
                    description = "Location dialing code; omit to leave unchanged, pass an empty string to clear")
            String locationDialingCode,
            @McpToolParam(required = false,
                    description = "Contact person's name; omit to leave unchanged, pass an empty string to clear")
            String contactName,
            @McpToolParam(required = false,
                    description = "Contact phone number; omit to leave unchanged, pass an empty string to clear")
            String contactNumber,
            @McpToolParam(required = false,
                    description = "Contact email address; omit to leave unchanged, pass an empty string to clear")
            String contactEmail,
            @McpToolParam(required = false,
                    description = "Address line 1; omit to leave unchanged, pass an empty string to clear")
            String addressLine1,
            @McpToolParam(required = false,
                    description = "Address line 2; omit to leave unchanged, pass an empty string to clear")
            String addressLine2,
            @McpToolParam(required = false,
                    description = "City; omit to leave unchanged, pass an empty string to clear")
            String city,
            @McpToolParam(required = false,
                    description = "State or province; use the full name (e.g. 'Georgia'), not the two-letter "
                            + "abbreviation (e.g. 'GA'), as BroadWorks rejects abbreviations with error 4015 "
                            + "('State or Province not valid'); omit to leave unchanged, pass an empty string "
                            + "to clear")
            String stateOrProvince,
            @McpToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unchanged, pass an empty string to clear")
            String zipOrPostalCode,
            @McpToolParam(required = false,
                    description = "Country; omit to leave unchanged, pass an empty string to clear")
            String country,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef ref = ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String grpId = require(ref.groupId(), "groupId");
        log.debug("tool broadworks_modify_group invoked (serviceProviderId={}, groupId={}, resourceId={})",
                spId, grpId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, spId);
            final Group group = Group.getPopulatedGroup(serviceProvider, grpId);
            final Group.GroupModifyRequest request = new Group.GroupModifyRequest(group);

            if (isPresent(groupName)) {
                request.setGroupName(groupName.trim());
            }
            if (isPresent(defaultDomain)) {
                request.setDefaultDomain(defaultDomain.trim());
            }
            if (userLimit != null) {
                request.setUserLimit(userLimit);
            }
            if (isPresent(timeZone)) {
                request.setTimeZone(timeZone.trim());
            }
            apply(callingLineIdName, request::setCallingLineIdName);
            apply(callingLineIdPhoneNumber, request::setCallingLineIdPhoneNumber);
            apply(locationDialingCode, request::setLocationDialingCode);

            // Build fresh Contact/StreetAddress objects and set only the supplied sub-fields. OCI treats an
            // omitted child element as "leave unchanged" and a nil element as "clear", so we must NOT resend
            // the current values of untouched sub-fields: doing so re-validates stale data (e.g. an invalid
            // stateOrProvince), which is what caused BroadWorks error 4015 for service providers.
            if (contactName != null || contactNumber != null || contactEmail != null) {
                final Contact contact = new Contact();
                apply(contactName, contact::setContactName);
                apply(contactNumber, contact::setContactNumber);
                apply(contactEmail, contact::setContactEmail);
                request.setContact(contact);
            }

            if (addressLine1 != null || addressLine2 != null || city != null
                    || stateOrProvince != null || zipOrPostalCode != null || country != null) {
                final StreetAddress address = new StreetAddress();
                apply(addressLine1, address::setAddressLine1);
                apply(addressLine2, address::setAddressLine2);
                apply(city, address::setCity);
                apply(stateOrProvince, address::setStateOrProvince);
                apply(zipOrPostalCode, address::setZipOrPostalCode);
                apply(country, address::setCountry);
                request.setAddress(address);
            }

            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "modify group " + spId + "/" + grpId);
            AlpacaRequests.flushResponseCache(server);

            final Group updated = Group.getPopulatedGroup(serviceProvider, grpId);
            log.debug("tool broadworks_modify_group succeeded (serviceProviderId={}, groupId={})",
                    spId, grpId);
            return toDetail(updated);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_modify_group failed for serviceProviderId={} groupId={}: {}",
                    spId, grpId, ex.getMessage());
            throw new AlpacaException("Group not found or not accessible: " + spId + "/" + grpId, ex);
        }
    }

    GroupDetail createGroup(String serviceProviderId, String groupId, String groupName,
            String defaultDomain, Integer userLimit, String timeZone, String callingLineIdName,
            String locationDialingCode, String contactName, String contactNumber, String contactEmail,
            String addressLine1, String addressLine2, String city, String stateOrProvince,
            String zipOrPostalCode, String country, String resourceId) {
        return createGroup(serviceProviderId, groupId, groupName, defaultDomain, userLimit, timeZone,
                callingLineIdName, locationDialingCode, contactName, contactNumber, contactEmail,
                addressLine1, addressLine2, city, stateOrProvince, zipOrPostalCode, country, resourceId,
                null);
    }

    @McpTool(name = "broadworks_create_group",
            description = "Create a new BroadWorks group within a service provider. This mutates live "
                    + "BroadWorks data. serviceProviderId, groupId, groupName, defaultDomain and userLimit are "
                    + "required. The optional timeZone, callingLineIdName, locationDialingCode, contact "
                    + "(name/number/email) and address fields are only sent when supplied — omit them entirely "
                    + "rather than sending placeholder values such as 'N/A' or '00000', otherwise BroadWorks may "
                    + "reject the request as invalid. If those required fields are omitted and the client supports "
                    + "elicitation, the server will request them. Fails if a group with the same id already exists "
                    + "in the service provider. Returns the newly created group detail.")
    public GroupDetail createGroup(
            @McpToolParam(required = false,
                    description = "The service provider id that will own the new group") String serviceProviderId,
            @McpToolParam(required = false,
                    description = "The id for the new group (must be unique within the service provider)")
            String groupId,
            @McpToolParam(required = false, description = "The display name for the new group") String groupName,
            @McpToolParam(required = false, description = "The default domain for the new group") String defaultDomain,
            @McpToolParam(required = false,
                    description = "The maximum number of users allowed in the new group") Integer userLimit,
            @McpToolParam(required = false,
                    description = "Time zone (e.g. 'America/New_York'); omit to leave unset")
            String timeZone,
            @McpToolParam(required = false,
                    description = "Calling line id name; omit to leave unset")
            String callingLineIdName,
            @McpToolParam(required = false,
                    description = "Location dialing code; omit to leave unset")
            String locationDialingCode,
            @McpToolParam(required = false,
                    description = "Contact person's name; omit to leave unset")
            String contactName,
            @McpToolParam(required = false,
                    description = "Contact phone number; omit to leave unset")
            String contactNumber,
            @McpToolParam(required = false,
                    description = "Contact email address; omit to leave unset")
            String contactEmail,
            @McpToolParam(required = false,
                    description = "Address line 1; omit to leave unset")
            String addressLine1,
            @McpToolParam(required = false,
                    description = "Address line 2; omit to leave unset")
            String addressLine2,
            @McpToolParam(required = false,
                    description = "City; omit to leave unset")
            String city,
            @McpToolParam(required = false,
                    description = "State or province; use the full name (e.g. 'Georgia'), not the two-letter "
                            + "abbreviation (e.g. 'GA'); omit to leave unset")
            String stateOrProvince,
            @McpToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unset")
            String zipOrPostalCode,
            @McpToolParam(required = false,
                    description = "Country; omit to leave unset")
            String country,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final CreateGroupDetails details = resolveCreateDetails(
                serviceProviderId, groupId, groupName, defaultDomain, userLimit, requestContext);
        log.debug("tool broadworks_create_group invoked (serviceProviderId={}, groupId={}, resourceId={})",
                details.serviceProviderId(), details.groupId(), resourceId);
        final String spId = require(details.serviceProviderId(), "serviceProviderId");
        final String grpId = require(details.groupId(), "groupId");
        final String grpName = require(details.groupName(), "groupName");
        final String domain = require(details.defaultDomain(), "defaultDomain");
        if (details.userLimit() == null) {
            throw new AlpacaException("userLimit is required");
        }
        final Integer limit = details.userLimit();
        final BroadWorksServer server = connect(resourceId);
        try {
            final Group.GroupConsolidatedAddRequest request =
                    new Group.GroupConsolidatedAddRequest(server, spId, domain, limit);
            request.setGroupId(grpId);
            request.setGroupName(grpName);
            if (isPresent(timeZone)) {
                request.setTimeZone(timeZone.trim());
            }
            apply(callingLineIdName, request::setCallingLineIdName);
            apply(locationDialingCode, request::setLocationDialingCode);

            if (contactName != null || contactNumber != null || contactEmail != null) {
                final Contact contact = new Contact();
                apply(contactName, contact::setContactName);
                apply(contactNumber, contact::setContactNumber);
                apply(contactEmail, contact::setContactEmail);
                request.setContact(contact);
            }

            if (addressLine1 != null || addressLine2 != null || city != null
                    || stateOrProvince != null || zipOrPostalCode != null || country != null) {
                final StreetAddress address = new StreetAddress();
                apply(addressLine1, address::setAddressLine1);
                apply(addressLine2, address::setAddressLine2);
                apply(city, address::setCity);
                apply(stateOrProvince, address::setStateOrProvince);
                apply(zipOrPostalCode, address::setZipOrPostalCode);
                apply(country, address::setCountry);
                request.setAddress(address);
            }

            final Group.GroupConsolidatedAddResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "create group " + spId + "/" + grpId);
            AlpacaRequests.flushResponseCache(server);

            final ServiceProvider serviceProvider = new ServiceProvider(server, spId);
            final Group created = Group.getPopulatedGroup(serviceProvider, grpId);
            log.debug("tool broadworks_create_group succeeded (serviceProviderId={}, groupId={})", spId, grpId);
            return toDetail(created);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_create_group failed for serviceProviderId={} groupId={}: {}",
                    spId, grpId, ex.getMessage());
            throw new AlpacaException("Group created but could not be read back: " + spId + "/" + grpId, ex);
        }
    }

    String deleteGroup(String serviceProviderId, String groupId, Boolean areYouSure, String resourceId) {
        return deleteGroup(serviceProviderId, groupId, areYouSure, resourceId, null);
    }

    @McpTool(name = "broadworks_delete_group",
            description = "Delete a BroadWorks group within a service provider. This mutates live "
                    + "BroadWorks data and is irreversible. BroadWorks may reject the deletion if the group "
                    + "still contains users. This is a two-step operation: first call without areYouSure "
                    + "(or with areYouSure=false) to receive a confirmation prompt; then call again with "
                    + "areYouSure=true to proceed. "
                    + "If serviceProviderId or groupId is omitted and the client supports elicitation, the "
                    + "server will request them. Returns a short confirmation message.")
    public String deleteGroup(
            @McpToolParam(required = false,
                    description = "The service provider id that owns the group")
            String serviceProviderId,
            @McpToolParam(required = false, description = "The id of the group to delete")
            String groupId,
            @McpToolParam(required = false,
                    description = "Must be true to actually delete. Call first without this (or with false) "
                            + "to get an Are you sure? prompt; then call again with areYouSure=true")
            Boolean areYouSure,
            @McpToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId,
            McpSyncRequestContext requestContext) {
        final ToolElicitation.GroupRef ref =
                ToolElicitation.resolveGroupRef(serviceProviderId, groupId, requestContext);
        final String spId = require(ref.serviceProviderId(), "serviceProviderId");
        final String grpId = require(ref.groupId(), "groupId");
        ToolElicitation.requireAreYouSure(areYouSure, "delete group '" + spId + "/" + grpId + "'");
        log.debug("tool broadworks_delete_group invoked (serviceProviderId={}, groupId={}, resourceId={})",
                spId, grpId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, spId);
            final Group group = Group.getPopulatedGroup(serviceProvider, grpId);
            final Group.GroupDeleteRequest request = new Group.GroupDeleteRequest(group);
            final DefaultResponse response = request.fire();
            AlpacaRequests.ensureSuccess(response, "delete group " + spId + "/" + grpId);
            AlpacaRequests.flushResponseCache(server);
            log.debug("tool broadworks_delete_group succeeded (serviceProviderId={}, groupId={})",
                    spId, grpId);
            return "Deleted group '" + grpId + "' from service provider " + spId;
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_delete_group failed: {}", ex.getMessage());
            throw ex;
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_delete_group failed for serviceProviderId={} groupId={}: {}",
                    spId, grpId, ex.getMessage());
            throw new AlpacaException("Group not found or not accessible: " + spId + "/" + grpId, ex);
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_delete_group failed unexpectedly: {}", ex.getMessage());
            throw new AlpacaException("Failed to delete group " + spId + "/" + grpId, ex);
        }
    }

    /** Maps a populated {@link Group} to a compact {@link GroupDetail} DTO. */
    private static GroupDetail toDetail(Group group) {
        return new GroupDetail(
                group.getGroupId(),
                group.getGroupName(),
                group.getServiceProviderId(),
                group.getDefaultDomain(),
                group.getUserCount(),
                group.getUserLimit(),
                group.getCallingLineIdName(),
                group.getCallingLineIdPhoneNumber(),
                group.getTimeZone(),
                group.getLocationDialingCode(),
                ContactAddressMapper.toContact(group.getContact()),
                ContactAddressMapper.toAddress(group.getAddress()));
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
     * {@code null} value leaves the field unchanged (the setter is never called), a blank value clears it
     * by passing {@code null} to the setter (mapped to {@link java.util.Optional#empty()} → a nil element),
     * and any other value sets the trimmed string. See {@code ServiceProviderTools#apply} for the full
     * rationale on why clearing goes through the setter rather than {@code unsetX()}.
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

    private static CreateGroupDetails resolveCreateDetails(String serviceProviderId, String groupId,
            String groupName, String defaultDomain, Integer userLimit,
            McpSyncRequestContext requestContext) {
        if ((!ToolElicitation.isBlank(serviceProviderId) && !ToolElicitation.isBlank(groupId)
                && !ToolElicitation.isBlank(groupName) && !ToolElicitation.isBlank(defaultDomain)
                && userLimit != null) || !ToolElicitation.canElicit(requestContext)) {
            return new CreateGroupDetails(serviceProviderId, groupId, groupName, defaultDomain, userLimit);
        }
        final CreateGroupDetails elicited = ToolElicitation.elicit(requestContext,
                "Service provider id, group id, display name, default domain, and user limit are required.",
                CreateGroupDetails.class,
                "serviceProviderId, groupId, groupName, defaultDomain and userLimit are required");
        final CreateGroupDetails merged = new CreateGroupDetails(
                ToolElicitation.firstNonBlank(serviceProviderId, elicited.serviceProviderId()),
                ToolElicitation.firstNonBlank(groupId, elicited.groupId()),
                ToolElicitation.firstNonBlank(groupName, elicited.groupName()),
                ToolElicitation.firstNonBlank(defaultDomain, elicited.defaultDomain()),
                ToolElicitation.firstNonNull(userLimit, elicited.userLimit()));
        log.info("Elicitation accepted for create group (serviceProviderId={}, groupId={})",
                merged.serviceProviderId(), merged.groupId());
        return merged;
    }

    /**
     * Required create fields the client may supply either as tool arguments or via elicitation.
     */
    record CreateGroupDetails(String serviceProviderId, String groupId, String groupName,
            String defaultDomain, Integer userLimit) {
    }
}
