package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

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
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import co.ecg.alpaca.toolkit.generated.enums.SearchMode;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable1Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable2Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable3Row;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
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
            return toDetail(user);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_get_user failed for userId={}: {}", userId, ex.getMessage());
            throw new AlpacaException("User not found or not accessible: " + userId, ex);
        }
    }

    @Tool(name = "broadworks_modify_user",
            description = "Modify a single BroadWorks user by their (system-unique) user id. This mutates live "
                    + "BroadWorks data. Only the fields you supply are changed (partial update); omit a field "
                    + "to leave it unchanged. Do NOT send placeholder values such as 'N/A' or '00000' for "
                    + "fields you are not changing \u2014 omit them entirely, otherwise BroadWorks may reject "
                    + "the request as invalid. For the clearable fields (phoneNumber, extension, emailAddress, "
                    + "title, mobilePhoneNumber, callingLineIdPhoneNumber and each address field) pass an empty "
                    + "string to clear the current value. firstName, lastName, timeZone, language, "
                    + "callingLineIdFirstName and callingLineIdLastName cannot be cleared and are only changed "
                    + "when a non-blank value is supplied. Passwords are intentionally not editable through this "
                    + "tool. Returns the refreshed user detail reflecting the applied state.")
    public UserDetail modifyUser(
            @ToolParam(description = "The (system-unique) id of the user to modify") String userId,
            @ToolParam(required = false,
                    description = "First name; omit to leave unchanged (cannot be cleared)")
            String firstName,
            @ToolParam(required = false,
                    description = "Last name; omit to leave unchanged (cannot be cleared)")
            String lastName,
            @ToolParam(required = false,
                    description = "Phone number; omit to leave unchanged, pass an empty string to clear")
            String phoneNumber,
            @ToolParam(required = false,
                    description = "Extension; omit to leave unchanged, pass an empty string to clear")
            String extension,
            @ToolParam(required = false,
                    description = "Email address; omit to leave unchanged, pass an empty string to clear")
            String emailAddress,
            @ToolParam(required = false,
                    description = "Title; omit to leave unchanged, pass an empty string to clear")
            String title,
            @ToolParam(required = false,
                    description = "Mobile phone number; omit to leave unchanged, pass an empty string to clear")
            String mobilePhoneNumber,
            @ToolParam(required = false,
                    description = "Time zone (e.g. 'America/New_York'); omit to leave unchanged (cannot be "
                            + "cleared)")
            String timeZone,
            @ToolParam(required = false,
                    description = "Language; omit to leave unchanged (cannot be cleared)")
            String language,
            @ToolParam(required = false,
                    description = "Calling line id first name; omit to leave unchanged (cannot be cleared)")
            String callingLineIdFirstName,
            @ToolParam(required = false,
                    description = "Calling line id last name; omit to leave unchanged (cannot be cleared)")
            String callingLineIdLastName,
            @ToolParam(required = false,
                    description = "Calling line id phone number; omit to leave unchanged, pass an empty string "
                            + "to clear")
            String callingLineIdPhoneNumber,
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
        log.debug("tool broadworks_modify_user invoked (userId={}, resourceId={})", userId, resourceId);
        final BroadWorksServer server = connect(resourceId);
        try {
            final User user = User.getPopulatedUser(server, userId);
            final User.UserModifyRequest request = new User.UserModifyRequest(user);

            if (isPresent(firstName)) {
                request.setFirstName(firstName.trim());
            }
            if (isPresent(lastName)) {
                request.setLastName(lastName.trim());
            }
            if (isPresent(timeZone)) {
                request.setTimeZone(timeZone.trim());
            }
            if (isPresent(language)) {
                request.setLanguage(language.trim());
            }
            if (isPresent(callingLineIdFirstName)) {
                request.setCallingLineIdFirstName(callingLineIdFirstName.trim());
            }
            if (isPresent(callingLineIdLastName)) {
                request.setCallingLineIdLastName(callingLineIdLastName.trim());
            }
            apply(phoneNumber, request::setPhoneNumber);
            apply(extension, request::setExtension);
            apply(emailAddress, request::setEmailAddress);
            apply(title, request::setTitle);
            apply(mobilePhoneNumber, request::setMobilePhoneNumber);
            apply(callingLineIdPhoneNumber, request::setCallingLineIdPhoneNumber);

            // Build a fresh StreetAddress and set only the supplied sub-fields. OCI treats an omitted child
            // element as "leave unchanged" and a nil element as "clear", so we must NOT resend the current
            // values of untouched sub-fields: doing so re-validates stale data (e.g. an invalid
            // stateOrProvince), which is what caused BroadWorks error 4015 for service providers.
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
            AlpacaRequests.ensureSuccess(response, "modify user " + userId);

            final User updated = User.getPopulatedUser(server, userId);
            log.debug("tool broadworks_modify_user succeeded (userId={})", userId);
            return toDetail(updated);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_modify_user failed for userId={}: {}", userId, ex.getMessage());
            throw new AlpacaException("User not found or not accessible: " + userId, ex);
        }
    }

    @Tool(name = "broadworks_create_user",
            description = "Create a new BroadWorks user within a group. This mutates live BroadWorks data. "
                    + "serviceProviderId, groupId, userId, firstName and lastName are required. "
                    + "callingLineIdFirstName and callingLineIdLastName default to firstName and lastName when "
                    + "omitted. Supplying a password is strongly recommended (BroadWorks may reject the user or "
                    + "leave it unusable without one). All other fields (phoneNumber, extension, emailAddress, "
                    + "title, mobilePhoneNumber, timeZone, language, callingLineIdPhoneNumber and each address "
                    + "field) are only sent when supplied — omit them entirely rather than sending placeholder "
                    + "values such as 'N/A' or '00000', otherwise BroadWorks may reject the request as invalid. "
                    + "Fails if a user with the same id already exists. Returns the newly created user detail.")
    public UserDetail createUser(
            @ToolParam(description = "The service provider id that owns the target group") String serviceProviderId,
            @ToolParam(description = "The group id the new user will belong to") String groupId,
            @ToolParam(description = "The id for the new user (must be unique system-wide)") String userId,
            @ToolParam(description = "First name") String firstName,
            @ToolParam(description = "Last name") String lastName,
            @ToolParam(required = false,
                    description = "Calling line id first name; defaults to firstName when omitted")
            String callingLineIdFirstName,
            @ToolParam(required = false,
                    description = "Calling line id last name; defaults to lastName when omitted")
            String callingLineIdLastName,
            @ToolParam(required = false,
                    description = "Initial password for the new user; strongly recommended")
            String password,
            @ToolParam(required = false,
                    description = "Phone number; omit to leave unset")
            String phoneNumber,
            @ToolParam(required = false,
                    description = "Extension; omit to leave unset")
            String extension,
            @ToolParam(required = false,
                    description = "Email address; omit to leave unset")
            String emailAddress,
            @ToolParam(required = false,
                    description = "Title; omit to leave unset")
            String title,
            @ToolParam(required = false,
                    description = "Mobile phone number; omit to leave unset")
            String mobilePhoneNumber,
            @ToolParam(required = false,
                    description = "Time zone (e.g. 'America/New_York'); omit to leave unset")
            String timeZone,
            @ToolParam(required = false,
                    description = "Language; omit to leave unset")
            String language,
            @ToolParam(required = false,
                    description = "Calling line id phone number; omit to leave unset")
            String callingLineIdPhoneNumber,
            @ToolParam(required = false,
                    description = "Address line 1; omit to leave unset")
            String addressLine1,
            @ToolParam(required = false,
                    description = "Address line 2; omit to leave unset")
            String addressLine2,
            @ToolParam(required = false,
                    description = "City; omit to leave unset")
            String city,
            @ToolParam(required = false,
                    description = "State or province; use the full name (e.g. 'Georgia'), not the two-letter "
                            + "abbreviation (e.g. 'GA'); omit to leave unset")
            String stateOrProvince,
            @ToolParam(required = false,
                    description = "ZIP or postal code; omit to leave unset")
            String zipOrPostalCode,
            @ToolParam(required = false,
                    description = "Country; omit to leave unset")
            String country,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        log.debug("tool broadworks_create_user invoked (serviceProviderId={}, groupId={}, userId={}, resourceId={})",
                serviceProviderId, groupId, userId, resourceId);
        final String spId = require(serviceProviderId, "serviceProviderId");
        final String grpId = require(groupId, "groupId");
        final String uId = require(userId, "userId");
        final String first = require(firstName, "firstName");
        final String last = require(lastName, "lastName");
        final String clidFirst = isPresent(callingLineIdFirstName) ? callingLineIdFirstName.trim() : first;
        final String clidLast = isPresent(callingLineIdLastName) ? callingLineIdLastName.trim() : last;
        final BroadWorksServer server = connect(resourceId);
        try {
            final User.UserAddRequest request = new User.UserAddRequest(
                    server, spId, grpId, uId, last, first, clidLast, clidFirst);
            if (isPresent(password)) {
                request.setPassword(password.trim());
            }
            if (isPresent(timeZone)) {
                request.setTimeZone(timeZone.trim());
            }
            if (isPresent(language)) {
                request.setLanguage(language.trim());
            }
            apply(phoneNumber, request::setPhoneNumber);
            apply(extension, request::setExtension);
            apply(emailAddress, request::setEmailAddress);
            apply(title, request::setTitle);
            apply(mobilePhoneNumber, request::setMobilePhoneNumber);
            apply(callingLineIdPhoneNumber, request::setCallingLineIdPhoneNumber);

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
            AlpacaRequests.ensureSuccess(response, "create user " + uId);

            final User created = User.getPopulatedUser(server, uId);
            log.debug("tool broadworks_create_user succeeded (userId={})", uId);
            return toDetail(created);
        } catch (BroadWorksObjectException ex) {
            log.warn("tool broadworks_create_user failed for userId={}: {}", uId, ex.getMessage());
            throw new AlpacaException("User created but could not be read back: " + uId, ex);
        }
    }

    /** Maps a populated {@link User} to a compact {@link UserDetail} DTO. */
    private static UserDetail toDetail(User user) {
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
}
