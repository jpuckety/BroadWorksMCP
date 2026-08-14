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
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
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
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable1Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable2Row;
import co.ecg.alpaca.toolkit.generated.tables.UserUserTable3Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class UserToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private UserTools tools;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new UserTools(connectionFactory);
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
}
