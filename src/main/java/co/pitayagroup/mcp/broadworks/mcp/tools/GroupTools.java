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
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks groups (within a service provider or system-wide), backed by the Alpaca toolkit.
 *
 * <p>Operations run against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject}); results are mapped to compact DTOs. No credentials or protocol bodies are
 * logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupTools {

    /** Columns emitted for each group row, in positional order. */
    static final List<String> GROUP_SCHEMA = List.of("groupId", "groupName", "userLimit");

    private final AlpacaConnectionFactory connectionFactory;

    @Tool(name = "broadworks_list_groups",
            description = "List (or search) BroadWorks groups. When a service provider id is supplied the search "
                    + "is scoped to that service provider; when it is omitted the search spans the entire system "
                    + "(all service providers). Pass an optional search value to filter by group name. Results "
                    + "are paginated and capped server-side (max " + Paging.MAX_PAGE_LIMIT + " per page): pass the "
                    + "returned next_cursor to fetch the next page and inspect has_more/total_matching to know "
                    + "when to stop. Rows are returned in a compact columnar form described by the schema field.")
    public Page listGroups(
            @ToolParam(required = false,
                    description = "The service provider id whose groups to list. Omit to search groups across "
                            + "the entire system (all service providers)")
            String serviceProviderId,
            @ToolParam(required = false,
                    description = "Optional case-insensitive filter matched against the group name; omit to "
                            + "list all")
            String search,
            @ToolParam(required = false,
                    description = "How the search value is matched: STARTSWITH, CONTAINS, or EQUALTO "
                            + "(default CONTAINS). Ignored when search is omitted")
            String searchMode,
            @ToolParam(required = false,
                    description = "Opaque pagination cursor returned as next_cursor by a previous call; "
                            + "omit to start from the first page")
            String cursor,
            @ToolParam(required = false,
                    description = "Maximum rows to return in this page. Clamped to the server ceiling of "
                            + Paging.MAX_PAGE_LIMIT + "; defaults to " + Paging.DEFAULT_PAGE_LIMIT + " when omitted")
            Integer limit,
            @ToolParam(required = false,
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

    @Tool(name = "broadworks_get_group",
            description = "Get details for a single BroadWorks group within a service provider, including its "
                    + "user count/limit, calling line id (name/number), time zone, location dialing code, "
                    + "contact (name/number/email), and physical address.")
    public GroupDetail getGroup(
            @ToolParam(description = "The service provider id") String serviceProviderId,
            @ToolParam(description = "The group id") String groupId,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_get_group invoked (serviceProviderId={}, groupId={}, resourceId={})",
                serviceProviderId, groupId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
            final Group group = Group.getPopulatedGroup(serviceProvider, groupId);
            return toDetail(group);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_group failed for serviceProviderId={} groupId={}: {}",
                    serviceProviderId, groupId, ex.getMessage());
            throw new AlpacaException("Group not found or not accessible: " + serviceProviderId + "/" + groupId, ex);
        }
    }

    @Tool(name = "broadworks_modify_group",
            description = "Modify a single BroadWorks group within a service provider. This mutates live "
                    + "BroadWorks data. Only the fields you supply are changed (partial update); omit a field "
                    + "to leave it unchanged. Do NOT send placeholder values such as 'N/A' or '00000' for "
                    + "fields you are not changing \u2014 omit them entirely, otherwise BroadWorks may reject "
                    + "the request as invalid. For the clearable fields (callingLineIdName, "
                    + "callingLineIdPhoneNumber, locationDialingCode and each contact/address field) pass an "
                    + "empty string to clear the current value. groupName, defaultDomain, timeZone and userLimit "
                    + "cannot be cleared and are only changed when a value is supplied. Returns the refreshed "
                    + "group detail reflecting the applied state.")
    public GroupDetail modifyGroup(
            @ToolParam(description = "The service provider id owning the group") String serviceProviderId,
            @ToolParam(description = "The id of the group to modify") String groupId,
            @ToolParam(required = false,
                    description = "New display name; omit to leave unchanged (cannot be cleared)")
            String groupName,
            @ToolParam(required = false,
                    description = "New default domain; omit to leave unchanged (cannot be cleared)")
            String defaultDomain,
            @ToolParam(required = false,
                    description = "New user limit; omit to leave unchanged (cannot be cleared)")
            Integer userLimit,
            @ToolParam(required = false,
                    description = "Calling line id name; omit to leave unchanged, pass an empty string to clear")
            String callingLineIdName,
            @ToolParam(required = false,
                    description = "Calling line id phone number; omit to leave unchanged, pass an empty string "
                            + "to clear")
            String callingLineIdPhoneNumber,
            @ToolParam(required = false,
                    description = "Time zone (e.g. 'America/New_York'); omit to leave unchanged (cannot be "
                            + "cleared)")
            String timeZone,
            @ToolParam(required = false,
                    description = "Location dialing code; omit to leave unchanged, pass an empty string to clear")
            String locationDialingCode,
            @ToolParam(required = false,
                    description = "Contact person's name; omit to leave unchanged, pass an empty string to clear")
            String contactName,
            @ToolParam(required = false,
                    description = "Contact phone number; omit to leave unchanged, pass an empty string to clear")
            String contactNumber,
            @ToolParam(required = false,
                    description = "Contact email address; omit to leave unchanged, pass an empty string to clear")
            String contactEmail,
            @ToolParam(required = false,
                    description = "Address line 1; omit to leave unchanged, pass an empty string to clear")
            String addressLine1,
            @ToolParam(required = false,
                    description = "Address line 2; omit to leave unchanged, pass an empty string to clear")
            String addressLine2,
            @ToolParam(required = false,
                    description = "City; omit to leave unchanged, pass an empty string to clear")
            String city,
            @ToolParam(required = false,
                    description = "State or province; use the full name (e.g. 'Georgia'), not the two-letter "
                            + "abbreviation (e.g. 'GA'), as BroadWorks rejects abbreviations with error 4015 "
                            + "('State or Province not valid'); omit to leave unchanged, pass an empty string "
                            + "to clear")
            String stateOrProvince,
            @ToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unchanged, pass an empty string to clear")
            String zipOrPostalCode,
            @ToolParam(required = false,
                    description = "Country; omit to leave unchanged, pass an empty string to clear")
            String country,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_modify_group invoked (serviceProviderId={}, groupId={}, resourceId={})",
                serviceProviderId, groupId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
            final Group group = Group.getPopulatedGroup(serviceProvider, groupId);
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
            AlpacaRequests.ensureSuccess(response, "modify group " + serviceProviderId + "/" + groupId);

            final Group updated = Group.getPopulatedGroup(serviceProvider, groupId);
            log.debug("tool broadworks_modify_group succeeded (serviceProviderId={}, groupId={})",
                    serviceProviderId, groupId);
            return toDetail(updated);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_modify_group failed for serviceProviderId={} groupId={}: {}",
                    serviceProviderId, groupId, ex.getMessage());
            throw new AlpacaException("Group not found or not accessible: " + serviceProviderId + "/" + groupId, ex);
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
}
