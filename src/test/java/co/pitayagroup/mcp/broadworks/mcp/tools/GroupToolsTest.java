package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.model.AddressInfo;
import co.pitayagroup.mcp.broadworks.mcp.model.ContactInfo;
import co.pitayagroup.mcp.broadworks.mcp.model.GroupDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.GroupSummary;
import co.pitayagroup.mcp.broadworks.mcp.model.Page;
import co.pitayagroup.mcp.broadworks.mcp.util.Paging;

import co.ecg.alpaca.toolkit.generated.Group;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import co.ecg.alpaca.toolkit.generated.tables.GroupGroupTable1Row;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class GroupToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private GroupTools tools;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new GroupTools(connectionFactory);
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
    void listGroupsMapsRowsToDtos() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final GroupGroupTable1Row row = mock(GroupGroupTable1Row.class);
        when(row.getGroupId()).thenReturn("grp-1");
        when(row.getGroupName()).thenReturn("Sales");
        when(row.getUserLimit()).thenReturn("25");

        final Group.GroupGetListInServiceProviderResponse response =
                mock(Group.GroupGetListInServiceProviderResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getGroupTable()).thenReturn(List.of(row));

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedConstruction<Group.GroupGetListInServiceProviderRequest> reqCtor =
                     mockConstruction(Group.GroupGetListInServiceProviderRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listGroups("sp-1", null, null, null, null, null);

            assertThat(result.schema()).containsExactly("groupId", "groupName", "userLimit");
            assertThat(result.rows()).containsExactly(Arrays.asList("grp-1", "Sales", "25"));
            assertThat(result.returned()).isEqualTo(1);
            assertThat(result.totalMatching()).isEqualTo(1);
            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
            assertThat(result.truncationReason()).isNull();
            assertThat(result.suggestion()).isNotBlank();
            assertThat(spCtor.constructed()).hasSize(1);
            assertThat(reqCtor.constructed()).hasSize(1);
        }
    }

    @Test
    void listGroupsReturnsEmptyPageWhenGroupTableIsNull() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group.GroupGetListInServiceProviderResponse response =
                mock(Group.GroupGetListInServiceProviderResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getGroupTable()).thenReturn(null);

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedConstruction<Group.GroupGetListInServiceProviderRequest> reqCtor =
                     mockConstruction(Group.GroupGetListInServiceProviderRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listGroups("sp-empty", null, null, null, null, null);

            assertThat(result.schema()).containsExactly("groupId", "groupName", "userLimit");
            assertThat(result.rows()).isEmpty();
            assertThat(result.returned()).isZero();
            assertThat(result.totalMatching()).isZero();
            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
            assertThat(result.truncationReason()).isNull();
            assertThat(result.suggestion()).isNotBlank();
        }
    }

    @Test
    void listGroupsSearchesSystemWideWhenServiceProviderOmitted() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final co.ecg.alpaca.toolkit.generated.tables.GroupGroupTable2Row row =
                mock(co.ecg.alpaca.toolkit.generated.tables.GroupGroupTable2Row.class);
        when(row.getGroupId()).thenReturn("grp-sys");
        when(row.getGroupName()).thenReturn("Sales");
        when(row.getUserLimit()).thenReturn("10");

        final Group.GroupGetListInSystemResponse response =
                mock(Group.GroupGetListInSystemResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getGroupTable()).thenReturn(List.of(row));

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedConstruction<Group.GroupGetListInSystemRequest> reqCtor =
                     mockConstruction(Group.GroupGetListInSystemRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listGroups(null, "sales", null, null, null, null);

            assertThat(result.schema()).containsExactly("groupId", "groupName", "userLimit");
            assertThat(result.rows()).containsExactly(Arrays.asList("grp-sys", "Sales", "10"));
            assertThat(result.returned()).isEqualTo(1);
            assertThat(result.totalMatching()).isEqualTo(1);
            assertThat(reqCtor.constructed()).hasSize(1);
            assertThat(spCtor.constructed()).isEmpty();
        }
    }

    @Test
    void toPageBuildsColumnarPagesAndPagingMetadata() {
        final List<GroupSummary> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(new GroupSummary("grp-" + i, "Name " + i, Integer.toString(i)));
        }

        final Page first = GroupTools.toPage(all, 0, 2);
        assertThat(first.schema()).isEqualTo(GroupTools.GROUP_SCHEMA);
        assertThat(first.rows()).containsExactly(
                Arrays.asList("grp-0", "Name 0", "0"),
                Arrays.asList("grp-1", "Name 1", "1"));
        assertThat(first.returned()).isEqualTo(2);
        assertThat(first.totalMatching()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.truncationReason()).isNotNull();
        assertThat(first.suggestion()).contains("broadworks_list_groups").contains(first.nextCursor());

        final Page second = GroupTools.toPage(all, Paging.decodeCursor(first.nextCursor()), 2);
        assertThat(second.rows()).containsExactly(Arrays.asList("grp-2", "Name 2", "2"));
        assertThat(second.returned()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.truncationReason()).isNull();
    }

    @Test
    void getGroupMapsToDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-2"))).thenReturn(null);

        final Group group = mock(Group.class);
        when(group.getGroupId()).thenReturn("grp-9");
        when(group.getGroupName()).thenReturn("Support");
        when(group.getServiceProviderId()).thenReturn("sp-1");
        when(group.getDefaultDomain()).thenReturn("sp1.example.com");
        when(group.getUserCount()).thenReturn(12);
        when(group.getUserLimit()).thenReturn(50);
        when(group.getCallingLineIdName()).thenReturn("Support Line");
        when(group.getCallingLineIdPhoneNumber()).thenReturn("+1-555-0199");
        when(group.getTimeZone()).thenReturn("America/Chicago");
        when(group.getLocationDialingCode()).thenReturn("217");

        final Contact contact = mock(Contact.class);
        when(contact.getContactName()).thenReturn(Optional.of("Jane Doe"));
        when(contact.getContactNumber()).thenReturn(Optional.of("+1-555-0100"));
        when(contact.getContactEmail()).thenReturn(Optional.of("jane@sp1.example.com"));
        when(group.getContact()).thenReturn(contact);

        final StreetAddress address = mock(StreetAddress.class);
        when(address.getAddressLine1()).thenReturn(Optional.of("1 Main St"));
        when(address.getAddressLine2()).thenReturn(Optional.of("Suite 200"));
        when(address.getCity()).thenReturn(Optional.of("Springfield"));
        when(address.getStateOrProvince()).thenReturn(Optional.of("IL"));
        when(address.getZipOrPostalCode()).thenReturn(Optional.of("62701"));
        when(address.getCountry()).thenReturn(Optional.of("US"));
        when(group.getAddress()).thenReturn(address);

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class)) {
            groupStatics.when(() -> Group.getPopulatedGroup(org.mockito.ArgumentMatchers.any(), eq("grp-9")))
                    .thenReturn(group);

            final GroupDetail detail = tools.getGroup("sp-1", "grp-9", "res-2");

            assertThat(detail).isEqualTo(new GroupDetail(
                    "grp-9", "Support", "sp-1", "sp1.example.com",
                    12, 50, "Support Line", "+1-555-0199", "America/Chicago", "217",
                    new ContactInfo("Jane Doe", "+1-555-0100", "jane@sp1.example.com"),
                    new AddressInfo("1 Main St", "Suite 200", "Springfield", "IL", "62701", "US")));
            assertThat(spCtor.constructed()).hasSize(1);
        }
    }

    @Test
    void getGroupMapsNullContactAndAddressToNull() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        when(group.getGroupId()).thenReturn("grp-9");
        when(group.getGroupName()).thenReturn("Support");
        when(group.getServiceProviderId()).thenReturn("sp-1");
        when(group.getDefaultDomain()).thenReturn("sp1.example.com");
        when(group.getUserCount()).thenReturn(null);
        when(group.getUserLimit()).thenReturn(null);
        when(group.getCallingLineIdName()).thenReturn(null);
        when(group.getCallingLineIdPhoneNumber()).thenReturn(null);
        when(group.getTimeZone()).thenReturn(null);
        when(group.getLocationDialingCode()).thenReturn(null);
        when(group.getContact()).thenReturn(null);
        when(group.getAddress()).thenReturn(null);

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class)) {
            groupStatics.when(() -> Group.getPopulatedGroup(org.mockito.ArgumentMatchers.any(), eq("grp-9")))
                    .thenReturn(group);

            final GroupDetail detail = tools.getGroup("sp-1", "grp-9", null);

            assertThat(detail).isEqualTo(new GroupDetail(
                    "grp-9", "Support", "sp-1", "sp1.example.com",
                    null, null, null, null, null, null, null, null));
            assertThat(detail.contact()).isNull();
            assertThat(detail.address()).isNull();
        }
    }
}
