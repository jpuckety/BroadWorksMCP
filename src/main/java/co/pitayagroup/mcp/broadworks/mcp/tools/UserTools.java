package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;
import java.util.List;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.Page;
import co.pitayagroup.mcp.broadworks.mcp.model.UserDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.UserSummary;
import co.pitayagroup.mcp.broadworks.mcp.util.AlpacaRequests;
import co.pitayagroup.mcp.broadworks.mcp.util.ContactAddressMapper;
import co.pitayagroup.mcp.broadworks.mcp.util.Paging;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.User;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaDn;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaEmailAddress;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaUserFirstName;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaUserId;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaUserLastName;
import co.ecg.alpaca.toolkit.generated.enums.SearchMode;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable1Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable2Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable3Row;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks users (within a group, a service provider, or system-wide), backed by the
 * Alpaca toolkit.
 *
 * <p>Operations run against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject}); results are mapped to compact DTOs. No credentials or protocol bodies are
 * logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserTools {

    /** Columns emitted for each user row, in positional order. */
    static final List<String> USER_SCHEMA = List.of(
            "userId", "groupId", "serviceProviderId", "lastName", "firstName",
            "phoneNumber", "extension", "emailAddress");

    private final AlpacaConnectionFactory connectionFactory;

    @Tool(name = "broadworks_list_users",
            description = "List (or search) BroadWorks users. The listing scope is derived from the supplied ids: "
                    + "when both a service provider id and a group id are given the search is scoped to that group; "
                    + "when only a service provider id is given it spans that service provider; when neither is "
                    + "given it spans the entire system. Supplying a group id without a service provider id is "
                    + "rejected. Each supplied search field (lastName, firstName, userId, phoneNumber, "
                    + "emailAddress) is combined as an AND criterion using the shared searchMode; omit all to list "
                    + "everything in scope. Results are paginated and capped server-side (max "
                    + Paging.MAX_PAGE_LIMIT + " per page): pass the returned next_cursor to fetch the next page and "
                    + "inspect has_more/total_matching to know when to stop. Rows are returned in a compact "
                    + "columnar form described by the schema field.")
    public Page listUsers(
            @ToolParam(required = false,
                    description = "The service provider id to scope the listing to. Omit (together with groupId) "
                            + "to search users across the entire system")
            String serviceProviderId,
            @ToolParam(required = false,
                    description = "The group id to scope the listing to. Requires serviceProviderId to also be "
                            + "supplied. Omit to search across the whole service provider or system")
            String groupId,
            @ToolParam(required = false,
                    description = "Optional filter matched against the user's last name")
            String lastName,
            @ToolParam(required = false,
                    description = "Optional filter matched against the user's first name")
            String firstName,
            @ToolParam(required = false,
                    description = "Optional filter matched against the user id")
            String userId,
            @ToolParam(required = false,
                    description = "Optional filter matched against the user's phone number")
            String phoneNumber,
            @ToolParam(required = false,
                    description = "Optional filter matched against the user's email address")
            String emailAddress,
            @ToolParam(required = false,
                    description = "How the search values are matched: STARTSWITH, CONTAINS, or EQUALTO "
                            + "(default CONTAINS). Applies to all supplied search fields")
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
        log.debug("tool broadworks_list_users invoked (serviceProviderId={}, groupId={}, lastName={}, firstName={}, "
                        + "userId={}, phoneNumber={}, emailAddress={}, searchMode={}, cursor={}, limit={}, "
                        + "resourceId={})", serviceProviderId, groupId, lastName, firstName, userId, phoneNumber,
                emailAddress, searchMode, cursor, limit, resourceId);
        final int offset = Paging.decodeCursor(cursor);
        final int pageLimit = Paging.effectivePageLimit(limit, USER_SCHEMA.size());
        final SearchMode mode = AlpacaRequests.searchMode(searchMode);
        final boolean hasServiceProvider = serviceProviderId != null && !serviceProviderId.isBlank();
        final boolean hasGroup = groupId != null && !groupId.isBlank();
        if (hasGroup && !hasServiceProvider) {
            throw new AlpacaException("A groupId requires a serviceProviderId to also be supplied");
        }
        final BroadWorksServer server = connect(resourceId);
        try {
            final List<UserSummary> summaries;
            if (hasGroup) {
                summaries = searchUsersInGroup(server, serviceProviderId, groupId,
                        mode, lastName, firstName, userId, phoneNumber, emailAddress);
            } else if (hasServiceProvider) {
                summaries = searchUsersInServiceProvider(server, serviceProviderId,
                        mode, lastName, firstName, userId, phoneNumber, emailAddress);
            } else {
                summaries = searchUsersInSystem(server,
                        mode, lastName, firstName, userId, phoneNumber, emailAddress);
            }
            final Page page = toPage(summaries, offset, pageLimit);
            log.debug("tool broadworks_list_users returning {} of {} user(s) (serviceProviderId={}, groupId={}, "
                            + "hasMore={})", page.returned(), page.totalMatching(), serviceProviderId, groupId,
                    page.hasMore());
            return page;
        } catch (AlpacaException ex) {
            log.warn("tool broadworks_list_users failed (serviceProviderId={}, groupId={}): {}",
                    serviceProviderId, groupId, ex.getMessage());
            throw ex;
        } catch (RuntimeException ex) {
            log.warn("tool broadworks_list_users failed unexpectedly (serviceProviderId={}, groupId={}): {}",
                    serviceProviderId, groupId, ex.getMessage());
            throw new AlpacaException("Failed to list users", ex);
        }
    }

    /**
     * Searches the users within a single group via {@code UserGetListInGroupRequest}, applying the
     * supplied search fields as AND criteria. Group-scope rows carry neither the group nor the service
     * provider id, so both are backfilled from the request parameters.
     */
    private static List<UserSummary> searchUsersInGroup(
            BroadWorksServer server, String serviceProviderId, String groupId, SearchMode mode,
            String lastName, String firstName, String userId, String phoneNumber, String emailAddress) {
        final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
        final User.UserGetListInGroupRequest request =
                new User.UserGetListInGroupRequest(serviceProvider, groupId);
        if (isPresent(lastName)) {
            request.setSearchCriteriaUserLastName(new SearchCriteriaUserLastName(mode, lastName.trim(), true));
        }
        if (isPresent(firstName)) {
            request.setSearchCriteriaUserFirstName(new SearchCriteriaUserFirstName(mode, firstName.trim(), true));
        }
        if (isPresent(userId)) {
            request.setSearchCriteriaUserId(new SearchCriteriaUserId(mode, userId.trim(), true));
        }
        if (isPresent(phoneNumber)) {
            request.setSearchCriteriaDn(new SearchCriteriaDn(mode, phoneNumber.trim(), true));
        }
        if (isPresent(emailAddress)) {
            request.setSearchCriteriaEmailAddress(new SearchCriteriaEmailAddress(mode, emailAddress.trim(), true));
        }
        final User.UserGetListInGroupResponse response = request.fire();
        AlpacaRequests.ensureSuccess(response, "list users in group");
        final List<UserUserTable1Row> userTable = response.getUserTable();
        return (userTable == null ? List.<UserUserTable1Row>of() : userTable)
                .stream()
                .map(row -> new UserSummary(
                        row.getUserId(),
                        groupId,
                        serviceProviderId,
                        row.getLastName(),
                        row.getFirstName(),
                        row.getPhoneNumber(),
                        row.getExtension(),
                        row.getEmailAddress()))
                .toList();
    }

    /**
     * Searches the users within a single service provider via {@code UserGetListInServiceProviderRequest},
     * applying the supplied search fields as AND criteria. Service-provider-scope rows carry the group id,
     * while the service provider id is backfilled from the request parameter.
     */
    private static List<UserSummary> searchUsersInServiceProvider(
            BroadWorksServer server, String serviceProviderId, SearchMode mode,
            String lastName, String firstName, String userId, String phoneNumber, String emailAddress) {
        final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
        final User.UserGetListInServiceProviderRequest request =
                new User.UserGetListInServiceProviderRequest(serviceProvider);
        if (isPresent(lastName)) {
            request.setSearchCriteriaUserLastName(new SearchCriteriaUserLastName(mode, lastName.trim(), true));
        }
        if (isPresent(firstName)) {
            request.setSearchCriteriaUserFirstName(new SearchCriteriaUserFirstName(mode, firstName.trim(), true));
        }
        if (isPresent(userId)) {
            request.setSearchCriteriaUserId(new SearchCriteriaUserId(mode, userId.trim(), true));
        }
        if (isPresent(phoneNumber)) {
            request.setSearchCriteriaDn(new SearchCriteriaDn(mode, phoneNumber.trim(), true));
        }
        if (isPresent(emailAddress)) {
            request.setSearchCriteriaEmailAddress(new SearchCriteriaEmailAddress(mode, emailAddress.trim(), true));
        }
        final User.UserGetListInServiceProviderResponse response = request.fire();
        AlpacaRequests.ensureSuccess(response, "list users in service provider");
        final List<UserUserTable2Row> userTable = response.getUserTable();
        return (userTable == null ? List.<UserUserTable2Row>of() : userTable)
                .stream()
                .map(row -> new UserSummary(
                        row.getUserId(),
                        row.getGroupId(),
                        serviceProviderId,
                        row.getLastName(),
                        row.getFirstName(),
                        row.getPhoneNumber(),
                        row.getExtension(),
                        row.getEmailAddress()))
                .toList();
    }

    /**
     * Searches users across the entire system via {@code UserGetListInSystemRequest}, applying the
     * supplied search fields as AND criteria. System-scope rows carry both the group and service provider
     * ids.
     */
    private static List<UserSummary> searchUsersInSystem(
            BroadWorksServer server, SearchMode mode,
            String lastName, String firstName, String userId, String phoneNumber, String emailAddress) {
        final User.UserGetListInSystemRequest request = new User.UserGetListInSystemRequest(server);
        if (isPresent(lastName)) {
            request.setSearchCriteriaUserLastName(new SearchCriteriaUserLastName(mode, lastName.trim(), true));
        }
        if (isPresent(firstName)) {
            request.setSearchCriteriaUserFirstName(new SearchCriteriaUserFirstName(mode, firstName.trim(), true));
        }
        if (isPresent(userId)) {
            request.setSearchCriteriaUserId(new SearchCriteriaUserId(mode, userId.trim(), true));
        }
        if (isPresent(phoneNumber)) {
            request.setSearchCriteriaDn(new SearchCriteriaDn(mode, phoneNumber.trim(), true));
        }
        if (isPresent(emailAddress)) {
            request.setSearchCriteriaEmailAddress(new SearchCriteriaEmailAddress(mode, emailAddress.trim(), true));
        }
        final User.UserGetListInSystemResponse response = request.fire();
        AlpacaRequests.ensureSuccess(response, "list users in system");
        final List<UserUserTable3Row> userTable = response.getUserTable();
        return (userTable == null ? List.<UserUserTable3Row>of() : userTable)
                .stream()
                .map(row -> new UserSummary(
                        row.getUserId(),
                        row.getGroupId(),
                        row.getServiceProviderId(),
                        row.getLastName(),
                        row.getFirstName(),
                        row.getPhoneNumber(),
                        row.getExtension(),
                        row.getEmailAddress()))
                .toList();
    }

    /**
     * Maps the user summaries to compact columnar rows and delegates to {@link Paging#toPage} to build
     * a server-capped {@link Page} with pagination metadata.
     */
    static Page toPage(List<UserSummary> summaries, int offset, int pageLimit) {
        final List<List<Object>> rows = summaries.stream()
                .map(u -> Arrays.<Object>asList(
                        u.userId(),
                        u.groupId(),
                        u.serviceProviderId(),
                        u.lastName(),
                        u.firstName(),
                        u.phoneNumber(),
                        u.extension(),
                        u.emailAddress()))
                .toList();
        return Paging.toPage(USER_SCHEMA, rows, offset, pageLimit, "broadworks_list_users", "users");
    }

    @Tool(name = "broadworks_get_user",
            description = "Get details for a single BroadWorks user by their (system-unique) user id, including "
                    + "their group and service provider, name, phone number/extension, email, department, title, "
                    + "mobile number, time zone, language, calling line id (name/number), and physical address.")
    public UserDetail getUser(
            @ToolParam(description = "The (system-unique) user id") String userId,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_get_user invoked (userId={}, resourceId={})", userId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final User user = User.getPopulatedUser(server, userId);
            return new UserDetail(
                    user.getUserId(),
                    user.getGroupId(),
                    user.getServiceProviderId(),
                    user.getFirstName(),
                    user.getLastName(),
                    user.getPhoneNumber(),
                    user.getExtension(),
                    user.getEmailAddress(),
                    user.getDepartmentFullPath(),
                    user.getTitle(),
                    user.getMobilePhoneNumber(),
                    user.getTimeZone(),
                    user.getLanguage(),
                    user.getCallingLineIdFirstName(),
                    user.getCallingLineIdLastName(),
                    user.getCallingLineIdPhoneNumber(),
                    ContactAddressMapper.toAddress(user.getAddress()));
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_user failed for userId={}: {}", userId, ex.getMessage());
            throw new AlpacaException("User not found or not accessible: " + userId, ex);
        }
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }
}
