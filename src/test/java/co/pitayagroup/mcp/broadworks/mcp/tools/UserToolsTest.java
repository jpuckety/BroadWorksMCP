package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.config.PublicBaseUrlProperties;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.approval.ConfirmationService;
import co.pitayagroup.mcp.broadworks.mcp.approval.InMemoryPendingApprovalStore;
import co.pitayagroup.mcp.broadworks.mcp.model.AddressInfo;
import co.pitayagroup.mcp.broadworks.mcp.model.Page;
import co.pitayagroup.mcp.broadworks.mcp.model.UserDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.UserSummary;
import co.pitayagroup.mcp.broadworks.mcp.util.Paging;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.User;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaEmailAddress;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaUserFirstName;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaUserId;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaUserLastName;
import co.ecg.alpaca.toolkit.generated.datatypes.SearchCriteriaDn;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable1Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable2Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable3Row;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.UserId;
import co.pitayagroup.mcp.broadworks.mcp.tools.UserTools.CreateUserDetails;

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class UserToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private UserTools tools;
    private McpSyncRequestContext requestContext;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new UserTools(connectionFactory, new ConfirmationService(
                new InMemoryPendingApprovalStore(), new PublicBaseUrlProperties("")));
        requestContext = mock(McpSyncRequestContext.class);
        final var principal = new DefaultOAuth2AuthenticatedPrincipal("sub-1",
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, "sub-1", UserInfo.EMAIL_ATTRIBUTE, "u@example.com"),
                List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void listUsersInGroupBackfillsScopeIdsFromParams() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final UserUserTable1Row row = mock(UserUserTable1Row.class);
        when(row.getUserId()).thenReturn("user-1@example.com");
        when(row.getLastName()).thenReturn("Doe");
        when(row.getFirstName()).thenReturn("Jane");
        when(row.getPhoneNumber()).thenReturn("+1-555-0100");
        when(row.getExtension()).thenReturn("1001");
        when(row.getEmailAddress()).thenReturn("jane@example.com");

        final User.UserGetListInGroupResponse response = mock(User.UserGetListInGroupResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getUserTable()).thenReturn(List.of(row));

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedConstruction<User.UserGetListInGroupRequest> reqCtor =
                     mockConstruction(User.UserGetListInGroupRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listUsers("sp-1", "grp-1", null, null, null, null, null,
                    null, null, null, null);

            assertThat(result.schema()).containsExactly(
                    "userId", "groupId", "serviceProviderId", "lastName", "firstName",
                    "phoneNumber", "extension", "emailAddress");
            assertThat(result.rows()).containsExactly(Arrays.asList(
                    "user-1@example.com", "grp-1", "sp-1", "Doe", "Jane",
                    "+1-555-0100", "1001", "jane@example.com"));
            assertThat(result.returned()).isEqualTo(1);
            assertThat(result.totalMatching()).isEqualTo(1);
            assertThat(result.hasMore()).isFalse();
            assertThat(spCtor.constructed()).hasSize(1);
            assertThat(reqCtor.constructed()).hasSize(1);
        }
    }

    @Test
    void listUsersInServiceProviderTakesGroupIdFromRow() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final UserUserTable2Row row = mock(UserUserTable2Row.class);
        when(row.getUserId()).thenReturn("user-2@example.com");
        when(row.getGroupId()).thenReturn("grp-from-row");
        when(row.getLastName()).thenReturn("Smith");
        when(row.getFirstName()).thenReturn("John");
        when(row.getPhoneNumber()).thenReturn("+1-555-0200");
        when(row.getExtension()).thenReturn("2002");
        when(row.getEmailAddress()).thenReturn("john@example.com");

        final User.UserGetListInServiceProviderResponse response =
                mock(User.UserGetListInServiceProviderResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getUserTable()).thenReturn(List.of(row));

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedConstruction<User.UserGetListInServiceProviderRequest> reqCtor =
                     mockConstruction(User.UserGetListInServiceProviderRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listUsers("sp-9", null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(result.rows()).containsExactly(Arrays.asList(
                    "user-2@example.com", "grp-from-row", "sp-9", "Smith", "John",
                    "+1-555-0200", "2002", "john@example.com"));
            assertThat(reqCtor.constructed()).hasSize(1);
            assertThat(spCtor.constructed()).hasSize(1);
        }
    }

    @Test
    void listUsersInSystemTakesBothIdsFromRow() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final UserUserTable3Row row = mock(UserUserTable3Row.class);
        when(row.getUserId()).thenReturn("user-3@example.com");
        when(row.getGroupId()).thenReturn("grp-row");
        when(row.getServiceProviderId()).thenReturn("sp-row");
        when(row.getLastName()).thenReturn("Nova");
        when(row.getFirstName()).thenReturn("Ada");
        when(row.getPhoneNumber()).thenReturn("+1-555-0300");
        when(row.getExtension()).thenReturn("3003");
        when(row.getEmailAddress()).thenReturn("ada@example.com");

        final User.UserGetListInSystemResponse response = mock(User.UserGetListInSystemResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getUserTable()).thenReturn(List.of(row));

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedConstruction<User.UserGetListInSystemRequest> reqCtor =
                     mockConstruction(User.UserGetListInSystemRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listUsers(null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(result.rows()).containsExactly(Arrays.asList(
                    "user-3@example.com", "grp-row", "sp-row", "Nova", "Ada",
                    "+1-555-0300", "3003", "ada@example.com"));
            assertThat(reqCtor.constructed()).hasSize(1);
            assertThat(spCtor.constructed()).isEmpty();
        }
    }

    @Test
    void listUsersAppliesOnlySuppliedSearchCriteria() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User.UserGetListInSystemResponse response = mock(User.UserGetListInSystemResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getUserTable()).thenReturn(List.of());

        try (MockedConstruction<User.UserGetListInSystemRequest> reqCtor =
                     mockConstruction(User.UserGetListInSystemRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            tools.listUsers(null, null, "Doe", null, null, null, "jane@example.com",
                    "STARTSWITH", null, null, null);

            final User.UserGetListInSystemRequest request = reqCtor.constructed().get(0);
            verify(request).setSearchCriteriaUserLastName(any(SearchCriteriaUserLastName.class));
            verify(request).setSearchCriteriaEmailAddress(any(SearchCriteriaEmailAddress.class));
            verify(request, never()).setSearchCriteriaUserFirstName(any(SearchCriteriaUserFirstName.class));
            verify(request, never()).setSearchCriteriaUserId(any(SearchCriteriaUserId.class));
            verify(request, never()).setSearchCriteriaDn(any(SearchCriteriaDn.class));
        }
    }

    @Test
    void listUsersReturnsEmptyPageWhenUserTableIsNull() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User.UserGetListInSystemResponse response = mock(User.UserGetListInSystemResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getUserTable()).thenReturn(null);

        try (MockedConstruction<User.UserGetListInSystemRequest> reqCtor =
                     mockConstruction(User.UserGetListInSystemRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listUsers(null, null, null, null, null, null, null,
                    null, null, null, null);

            assertThat(result.rows()).isEmpty();
            assertThat(result.returned()).isZero();
            assertThat(result.totalMatching()).isZero();
            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
        }
    }

    @Test
    void listUsersRejectsGroupIdWithoutServiceProviderId() {
        assertThatThrownBy(() -> tools.listUsers(null, "grp-1", null, null, null, null, null,
                null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("groupId requires a serviceProviderId");
    }

    @Test
    void toPageBuildsColumnarPagesAndPagingMetadata() {
        final List<UserSummary> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(new UserSummary("user-" + i, "grp", "sp", "Last " + i, "First " + i,
                    "+1-555-000" + i, Integer.toString(i), "u" + i + "@example.com"));
        }

        final Page first = UserTools.toPage(all, 0, 2);
        assertThat(first.schema()).isEqualTo(UserTools.USER_SCHEMA);
        assertThat(first.rows()).containsExactly(
                Arrays.asList("user-0", "grp", "sp", "Last 0", "First 0", "+1-555-0000", "0", "u0@example.com"),
                Arrays.asList("user-1", "grp", "sp", "Last 1", "First 1", "+1-555-0001", "1", "u1@example.com"));
        assertThat(first.returned()).isEqualTo(2);
        assertThat(first.totalMatching()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.truncationReason()).isNotNull();
        assertThat(first.suggestion()).contains("broadworks_list_users").contains(first.nextCursor());

        final Page second = UserTools.toPage(all, Paging.decodeCursor(first.nextCursor()), 2);
        assertThat(second.rows()).containsExactly(
                Arrays.asList("user-2", "grp", "sp", "Last 2", "First 2", "+1-555-0002", "2", "u2@example.com"));
        assertThat(second.returned()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
    }

    @Test
    void getUserMapsToDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-2"))).thenReturn(null);

        final User user = mock(User.class);
        when(user.getUserId()).thenReturn("user-9@example.com");
        when(user.getGroupId()).thenReturn("grp-9");
        when(user.getServiceProviderId()).thenReturn("sp-1");
        when(user.getFirstName()).thenReturn("Jane");
        when(user.getLastName()).thenReturn("Doe");
        when(user.getPhoneNumber()).thenReturn("+1-555-0100");
        when(user.getExtension()).thenReturn("1001");
        when(user.getEmailAddress()).thenReturn("jane@example.com");
        when(user.getDepartmentFullPath()).thenReturn("Sales/West");
        when(user.getTitle()).thenReturn("Manager");
        when(user.getMobilePhoneNumber()).thenReturn("+1-555-0199");
        when(user.getTimeZone()).thenReturn("America/Chicago");
        when(user.getLanguage()).thenReturn("English");
        when(user.getCallingLineIdFirstName()).thenReturn("J");
        when(user.getCallingLineIdLastName()).thenReturn("D");
        when(user.getCallingLineIdPhoneNumber()).thenReturn("+1-555-0101");

        final co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress address =
                mock(co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress.class);
        when(address.getAddressLine1()).thenReturn(Optional.of("1 Main St"));
        when(address.getAddressLine2()).thenReturn(Optional.empty());
        when(address.getCity()).thenReturn(Optional.of("Springfield"));
        when(address.getStateOrProvince()).thenReturn(Optional.of("IL"));
        when(address.getZipOrPostalCode()).thenReturn(Optional.of("62701"));
        when(address.getCountry()).thenReturn(Optional.of("US"));
        when(user.getAddress()).thenReturn(address);

        try (MockedStatic<User> userStatics = mockStatic(User.class)) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-9@example.com"))).thenReturn(user);

            final UserDetail detail = tools.getUser("user-9@example.com", "res-2");

            assertThat(detail).isEqualTo(new UserDetail(
                    "user-9@example.com", "grp-9", "sp-1", "Jane", "Doe",
                    "+1-555-0100", "1001", "jane@example.com", "Sales/West", "Manager",
                    "+1-555-0199", "America/Chicago", "English", "J", "D", "+1-555-0101",
                    new AddressInfo("1 Main St", null, "Springfield", "IL", "62701", "US")));
        }
    }

    @Test
    void getUserMapsNotFoundToAlpacaException() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        try (MockedStatic<User> userStatics = mockStatic(User.class)) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("missing")))
                    .thenThrow(new BroadWorksObjectException("nope"));

            assertThatThrownBy(() -> tools.getUser("missing", null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("User not found or not accessible: missing");
        }
    }

    @Test
    void modifyUserAppliesSuppliedFieldsAndReturnsRefreshedDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);

        final User refreshed = mock(User.class);
        when(refreshed.getUserId()).thenReturn("user-9@example.com");
        when(refreshed.getGroupId()).thenReturn("grp-9");
        when(refreshed.getServiceProviderId()).thenReturn("sp-1");
        when(refreshed.getFirstName()).thenReturn("Janet");
        when(refreshed.getLastName()).thenReturn("Doe");
        when(refreshed.getPhoneNumber()).thenReturn("+1-555-0200");
        when(refreshed.getExtension()).thenReturn("1001");
        when(refreshed.getEmailAddress()).thenReturn("janet@example.com");
        when(refreshed.getDepartmentFullPath()).thenReturn("Sales/West");
        when(refreshed.getTitle()).thenReturn("Director");
        when(refreshed.getMobilePhoneNumber()).thenReturn("+1-555-0199");
        when(refreshed.getTimeZone()).thenReturn("America/New_York");
        when(refreshed.getLanguage()).thenReturn("English");
        when(refreshed.getCallingLineIdFirstName()).thenReturn("J");
        when(refreshed.getCallingLineIdLastName()).thenReturn("D");
        when(refreshed.getCallingLineIdPhoneNumber()).thenReturn("+1-555-0101");
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserModifyRequest> reqCtor =
                     mockConstruction(User.UserModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-9@example.com")))
                    .thenReturn(user, refreshed);

            final UserDetail detail = tools.modifyUser(
                    "user-9@example.com", "Janet", null, "+1-555-0200", null,
                    "janet@example.com", "Director", null, "America/New_York", null,
                    null, null, null,
                    null, null, "Metropolis", null, null, null, null);

            final User.UserModifyRequest req = reqCtor.constructed().get(0);
            verify(req).setFirstName("Janet");
            verify(req).setPhoneNumber("+1-555-0200");
            verify(req).setEmailAddress("janet@example.com");
            verify(req).setTitle("Director");
            verify(req).setTimeZone("America/New_York");
            verify(req, never()).setLastName(any());

            // Only supplied sub-fields land on the fresh StreetAddress; untouched ones stay unset
            // (raw null Optional) so they are omitted from the OCI request rather than re-sent.
            final ArgumentCaptor<StreetAddress> addressCaptor = ArgumentCaptor.forClass(StreetAddress.class);
            verify(req).setAddress(addressCaptor.capture());
            assertThat(addressCaptor.getValue().getCity()).contains("Metropolis");
            assertThat(addressCaptor.getValue().getCountry()).isNull();

            assertThat(detail).isEqualTo(new UserDetail(
                    "user-9@example.com", "grp-9", "sp-1", "Janet", "Doe",
                    "+1-555-0200", "1001", "janet@example.com", "Sales/West", "Director",
                    "+1-555-0199", "America/New_York", "English", "J", "D", "+1-555-0101", null));
        }
    }

    @Test
    void modifyUserClearsClearableFieldsWithEmptyStringButNeverClearsName() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);

        final User refreshed = mock(User.class);
        when(refreshed.getUserId()).thenReturn("user-9@example.com");
        when(refreshed.getGroupId()).thenReturn("grp-9");
        when(refreshed.getServiceProviderId()).thenReturn("sp-1");
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserModifyRequest> reqCtor =
                     mockConstruction(User.UserModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-9@example.com")))
                    .thenReturn(user, refreshed);

            tools.modifyUser(
                    "user-9@example.com", "", null, "", null,
                    null, "", null, null, null,
                    null, null, null,
                    null, null, null, null, null, null, null);

            final User.UserModifyRequest req = reqCtor.constructed().get(0);
            // Name is never cleared: a blank first name is ignored (never passed to the setter).
            verify(req, never()).setFirstName(any());
            // A blank clearable field clears via setX(null) -> Optional.empty() (nil), NOT unsetX().
            verify(req).setPhoneNumber(null);
            verify(req, never()).unsetPhoneNumber();
            verify(req).setTitle(null);
            verify(req, never()).unsetTitle();
        }
    }

    @Test
    void modifyUserThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4010");

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserModifyRequest> ignored =
                     mockConstruction(User.UserModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-9@example.com")))
                    .thenReturn(user);

            assertThatThrownBy(() -> tools.modifyUser(
                    "user-9@example.com", "Janet", null, null, null,
                    null, null, null, null, null,
                    null, null, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("modify user");
        }
    }

    @Test
    void createUserSendsRequiredFieldsDefaultsClidAndReturnsDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User created = mock(User.class);
        when(created.getUserId()).thenReturn("user-new@example.com");
        when(created.getGroupId()).thenReturn("grp-1");
        when(created.getServiceProviderId()).thenReturn("sp-1");
        when(created.getFirstName()).thenReturn("Jane");
        when(created.getLastName()).thenReturn("Doe");
        when(created.getPhoneNumber()).thenReturn("+1-555-0100");
        when(created.getExtension()).thenReturn("1001");
        when(created.getEmailAddress()).thenReturn("jane@example.com");
        when(created.getDepartmentFullPath()).thenReturn(null);
        when(created.getTitle()).thenReturn("Manager");
        when(created.getMobilePhoneNumber()).thenReturn(null);
        when(created.getTimeZone()).thenReturn("America/New_York");
        when(created.getLanguage()).thenReturn("English");
        when(created.getCallingLineIdFirstName()).thenReturn("Jane");
        when(created.getCallingLineIdLastName()).thenReturn("Doe");
        when(created.getCallingLineIdPhoneNumber()).thenReturn(null);
        when(created.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        final List<List<Object>> ctorArgs = new ArrayList<>();
        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserAddRequest> reqCtor =
                     mockConstruction(User.UserAddRequest.class,
                             (m, ctx) -> {
                                 ctorArgs.add(new ArrayList<>(ctx.arguments()));
                                 when(m.fire()).thenReturn(response);
                             })) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-new@example.com")))
                    .thenReturn(created);

            final UserDetail detail = tools.createUser(
                    "sp-1", "grp-1", "user-new@example.com", "Jane", "Doe",
                    null, null, "s3cret", "+1-555-0100", "1001",
                    "jane@example.com", "Manager", null, "America/New_York", "English",
                    null, null, null, "Springfield", null, null, null, null);

            // callingLineId first/last default to first/last name (constructor arg order:
            // server, serviceProviderId, groupId, userId, lastName, firstName, clidLastName, clidFirstName).
            assertThat(ctorArgs.get(0)).containsExactly(
                    null, "sp-1", "grp-1", "user-new@example.com", "Doe", "Jane", "Doe", "Jane");

            final User.UserAddRequest req = reqCtor.constructed().get(0);
            verify(req).setPassword("s3cret");
            verify(req).setTimeZone("America/New_York");
            verify(req).setLanguage("English");
            verify(req).setPhoneNumber("+1-555-0100");
            verify(req).setExtension("1001");
            verify(req).setEmailAddress("jane@example.com");
            verify(req).setTitle("Manager");

            final ArgumentCaptor<StreetAddress> addressCaptor = ArgumentCaptor.forClass(StreetAddress.class);
            verify(req).setAddress(addressCaptor.capture());
            assertThat(addressCaptor.getValue().getCity()).contains("Springfield");

            assertThat(detail).isEqualTo(new UserDetail(
                    "user-new@example.com", "grp-1", "sp-1", "Jane", "Doe",
                    "+1-555-0100", "1001", "jane@example.com", null, "Manager",
                    null, "America/New_York", "English", "Jane", "Doe", null, null));
        }
    }

    @Test
    void createUserRejectsMissingRequiredField() {
        assertThatThrownBy(() -> tools.createUser(
                "sp-1", "grp-1", "  ", "Jane", "Doe",
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("userId is required");
    }

    @Test
    void createUserThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4010");

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserAddRequest> ignored =
                     mockConstruction(User.UserAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            assertThatThrownBy(() -> tools.createUser(
                    "sp-1", "grp-1", "user-dup@example.com", "Jane", "Doe",
                    null, null, "s3cret", null, null,
                    null, null, null, null, null,
                    null, null, null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("create user");
        }
    }

    @Test
    void getUserDoesNotElicitWhenIdPresent() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        when(user.getUserId()).thenReturn("user-9@example.com");
        when(user.getGroupId()).thenReturn("grp-9");
        when(user.getServiceProviderId()).thenReturn("sp-1");
        when(user.getAddress()).thenReturn(null);

        try (MockedStatic<User> userStatics = mockStatic(User.class)) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-9@example.com"))).thenReturn(user);

            tools.getUser("user-9@example.com", null, requestContext);

            verify(requestContext, never()).elicitEnabled();
            verify(requestContext, never()).elicit(any(), eq(UserId.class));
        }
    }

    @Test
    void getUserUsesElicitedIdWhenMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(UserId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new UserId("user-9@example.com"), Map.of()));

        final User user = mock(User.class);
        when(user.getUserId()).thenReturn("user-9@example.com");
        when(user.getGroupId()).thenReturn("grp-9");
        when(user.getServiceProviderId()).thenReturn("sp-1");
        when(user.getAddress()).thenReturn(null);

        try (MockedStatic<User> userStatics = mockStatic(User.class)) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-9@example.com"))).thenReturn(user);

            final UserDetail detail = tools.getUser(null, null, requestContext);

            assertThat(detail.userId()).isEqualTo("user-9@example.com");
        }
    }

    @Test
    void createUserUsesElicitedRequiredFieldsWhenMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(CreateUserDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new CreateUserDetails("sp-1", "grp-1", "user-new@example.com", "Jane", "Doe"),
                        Map.of()));

        final User created = mock(User.class);
        when(created.getUserId()).thenReturn("user-new@example.com");
        when(created.getGroupId()).thenReturn("grp-1");
        when(created.getServiceProviderId()).thenReturn("sp-1");
        when(created.getFirstName()).thenReturn("Jane");
        when(created.getLastName()).thenReturn("Doe");
        when(created.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        final List<List<Object>> ctorArgs = new ArrayList<>();
        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserAddRequest> reqCtor =
                     mockConstruction(User.UserAddRequest.class,
                             (m, ctx) -> {
                                 ctorArgs.add(new ArrayList<>(ctx.arguments()));
                                 when(m.fire()).thenReturn(response);
                             })) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-new@example.com")))
                    .thenReturn(created);

            final UserDetail detail = tools.createUser(
                    null, null, null, null, null,
                    null, null, "s3cret", null, null,
                    null, null, null, null, null,
                    null, null, null, null, null, null, null, null, requestContext);

            assertThat(ctorArgs.get(0)).containsExactly(
                    null, "sp-1", "grp-1", "user-new@example.com", "Doe", "Jane", "Doe", "Jane");
            verify(reqCtor.constructed().get(0)).setPassword("s3cret");
            verify(requestContext).elicit(any(), eq(CreateUserDetails.class));
            assertThat(detail.userId()).isEqualTo("user-new@example.com");
        }
    }

    @Test
    void createUserDoesNotElicitPassword() {
        assertThat(CreateUserDetails.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("serviceProviderId", "groupId", "userId", "firstName", "lastName")
                .doesNotContain("password");
    }

    @Test
    void createUserFailsWhenElicitationDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(CreateUserDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() -> tools.createUser(
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("serviceProviderId, groupId, userId, firstName and lastName are required");
        verify(connectionFactory, never()).connect(any(), any());
    }

    @Test
    void getUserFailsWhenElicitationDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(UserId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() -> tools.getUser(null, null, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("userId is required");
        verify(connectionFactory, never()).connect(any(), any());
    }

    @Test
    void deleteUserReturnsConfirmation() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserDeleteRequest> mocked =
                     mockConstruction(User.UserDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1@example.com"))).thenReturn(user);

            final String result = tools.deleteUser("user-1@example.com", true, null);

            assertThat(mocked.constructed()).hasSize(1);
            assertThat(result).contains("user-1@example.com");
        }
    }

    @Test
    void deleteUserRequiresAreYouSure() {
        assertThatThrownBy(() -> tools.deleteUser("user-1@example.com", null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure")
                .hasMessageContaining("areYouSure=true");
        assertThatThrownBy(() -> tools.deleteUser("user-1@example.com", false, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure");
        verify(connectionFactory, never()).connect(any(), any());
    }

    @Test
    void deleteUserThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4008");

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserDeleteRequest> ignored =
                     mockConstruction(User.UserDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1@example.com"))).thenReturn(user);

            assertThatThrownBy(() -> tools.deleteUser("user-1@example.com", true, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("delete user");
        }
    }
}
