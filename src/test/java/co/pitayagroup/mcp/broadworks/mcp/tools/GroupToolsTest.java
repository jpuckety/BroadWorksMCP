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
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.pitayagroup.mcp.broadworks.mcp.tools.GroupTools.CreateGroupDetails;
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.GroupRef;

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

@ExtendWith(MockitoExtension.class)
class GroupToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private GroupTools tools;

    @Mock
    private McpSyncRequestContext requestContext;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new GroupTools(connectionFactory, new ConfirmationService(
                new InMemoryPendingApprovalStore(), new PublicBaseUrlProperties("")));
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

    @Test
    void modifyGroupAppliesSuppliedFieldsAndReturnsRefreshedDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);

        final Group refreshed = mock(Group.class);
        when(refreshed.getGroupId()).thenReturn("grp-9");
        when(refreshed.getGroupName()).thenReturn("Support New");
        when(refreshed.getServiceProviderId()).thenReturn("sp-1");
        when(refreshed.getDefaultDomain()).thenReturn("sp1.example.com");
        when(refreshed.getUserCount()).thenReturn(12);
        when(refreshed.getUserLimit()).thenReturn(75);
        when(refreshed.getCallingLineIdName()).thenReturn("New CLID");
        when(refreshed.getCallingLineIdPhoneNumber()).thenReturn(null);
        when(refreshed.getTimeZone()).thenReturn("America/New_York");
        when(refreshed.getLocationDialingCode()).thenReturn(null);
        when(refreshed.getContact()).thenReturn(null);
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupModifyRequest> reqCtor =
                     mockConstruction(Group.GroupModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-9")))
                    .thenReturn(group, refreshed);

            final GroupDetail detail = tools.modifyGroup(
                    "sp-1", "grp-9", "Support New", null, 75,
                    "New CLID", null, "America/New_York", null,
                    null, null, "jane@sp1.example.com",
                    null, null, "Metropolis", null, null, null, null);

            final Group.GroupModifyRequest req = reqCtor.constructed().get(0);
            org.mockito.Mockito.verify(req).setGroupName("Support New");
            org.mockito.Mockito.verify(req).setUserLimit(75);
            org.mockito.Mockito.verify(req).setCallingLineIdName("New CLID");
            org.mockito.Mockito.verify(req).setTimeZone("America/New_York");
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).setDefaultDomain(any());

            // Only supplied sub-fields land on the fresh Contact/StreetAddress; untouched ones stay unset
            // (raw null Optional) so they are omitted from the OCI request rather than re-sent.
            final ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(contactCaptor.capture());
            assertThat(contactCaptor.getValue().getContactEmail()).contains("jane@sp1.example.com");
            assertThat(contactCaptor.getValue().getContactName()).isNull();

            final ArgumentCaptor<StreetAddress> addressCaptor = ArgumentCaptor.forClass(StreetAddress.class);
            org.mockito.Mockito.verify(req).setAddress(addressCaptor.capture());
            assertThat(addressCaptor.getValue().getCity()).contains("Metropolis");
            assertThat(addressCaptor.getValue().getCountry()).isNull();

            assertThat(detail).isEqualTo(new GroupDetail(
                    "grp-9", "Support New", "sp-1", "sp1.example.com",
                    12, 75, "New CLID", null, "America/New_York", null, null, null));
        }
    }

    @Test
    void modifyGroupClearsClearableFieldsWithEmptyStringButNeverClearsName() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);

        final Group refreshed = mock(Group.class);
        when(refreshed.getGroupId()).thenReturn("grp-9");
        when(refreshed.getGroupName()).thenReturn("Support");
        when(refreshed.getServiceProviderId()).thenReturn("sp-1");
        when(refreshed.getDefaultDomain()).thenReturn("sp1.example.com");
        when(refreshed.getUserCount()).thenReturn(null);
        when(refreshed.getUserLimit()).thenReturn(null);
        when(refreshed.getCallingLineIdName()).thenReturn(null);
        when(refreshed.getCallingLineIdPhoneNumber()).thenReturn(null);
        when(refreshed.getTimeZone()).thenReturn(null);
        when(refreshed.getLocationDialingCode()).thenReturn(null);
        when(refreshed.getContact()).thenReturn(null);
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupModifyRequest> reqCtor =
                     mockConstruction(Group.GroupModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-9")))
                    .thenReturn(group, refreshed);

            tools.modifyGroup(
                    "sp-1", "grp-9", "", null, null,
                    "", null, null, null,
                    "", null, null,
                    null, null, null, null, null, null, null);

            final Group.GroupModifyRequest req = reqCtor.constructed().get(0);
            // Name is never cleared: a blank display name is ignored (never passed to the setter).
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).setGroupName(any());
            // A blank clearable field clears via setX(null) -> Optional.empty() (nil), NOT unsetX().
            org.mockito.Mockito.verify(req).setCallingLineIdName(null);
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).unsetCallingLineIdName();

            final ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(contactCaptor.capture());
            // Cleared field is present-but-empty (Optional.empty()), which serializes as a nil element.
            assertThat(contactCaptor.getValue().getContactName()).isNotNull().isEmpty();
        }
    }

    @Test
    void modifyGroupThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4010");

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupModifyRequest> ignored =
                     mockConstruction(Group.GroupModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-9")))
                    .thenReturn(group);

            assertThatThrownBy(() -> tools.modifyGroup(
                    "sp-1", "grp-9", "Support New", null, null,
                    null, null, null, null,
                    null, null, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("modify group");
        }
    }

    @Test
    void createGroupSendsRequiredFieldsAndReturnsDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group created = mock(Group.class);
        when(created.getGroupId()).thenReturn("grp-new");
        when(created.getGroupName()).thenReturn("Support");
        when(created.getServiceProviderId()).thenReturn("sp-1");
        when(created.getDefaultDomain()).thenReturn("sp1.example.com");
        when(created.getUserCount()).thenReturn(0);
        when(created.getUserLimit()).thenReturn(50);
        when(created.getCallingLineIdName()).thenReturn("Main CLID");
        when(created.getCallingLineIdPhoneNumber()).thenReturn(null);
        when(created.getTimeZone()).thenReturn("America/New_York");
        when(created.getLocationDialingCode()).thenReturn(null);
        when(created.getContact()).thenReturn(null);
        when(created.getAddress()).thenReturn(null);

        final Group.GroupConsolidatedAddResponse response =
                mock(Group.GroupConsolidatedAddResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupConsolidatedAddRequest> reqCtor =
                     mockConstruction(Group.GroupConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-new")))
                    .thenReturn(created);

            final GroupDetail detail = tools.createGroup(
                    "sp-1", "grp-new", "Support", "sp1.example.com", 50,
                    "America/New_York", "Main CLID", null,
                    "Jane", null, null,
                    null, null, "Metropolis", null, null, null, null);

            final Group.GroupConsolidatedAddRequest req = reqCtor.constructed().get(0);
            org.mockito.Mockito.verify(req).setGroupId("grp-new");
            org.mockito.Mockito.verify(req).setGroupName("Support");
            org.mockito.Mockito.verify(req).setTimeZone("America/New_York");
            org.mockito.Mockito.verify(req).setCallingLineIdName("Main CLID");

            final ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(contactCaptor.capture());
            assertThat(contactCaptor.getValue().getContactName()).contains("Jane");

            final ArgumentCaptor<StreetAddress> addressCaptor = ArgumentCaptor.forClass(StreetAddress.class);
            org.mockito.Mockito.verify(req).setAddress(addressCaptor.capture());
            assertThat(addressCaptor.getValue().getCity()).contains("Metropolis");

            assertThat(detail).isEqualTo(new GroupDetail(
                    "grp-new", "Support", "sp-1", "sp1.example.com",
                    0, 50, "Main CLID", null, "America/New_York", null, null, null));
        }
    }

    @Test
    void createGroupRejectsMissingUserLimit() {
        assertThatThrownBy(() -> tools.createGroup(
                "sp-1", "grp-new", "Support", "sp1.example.com", null,
                null, null, null,
                null, null, null,
                null, null, null, null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("userLimit is required");
    }

    @Test
    void createGroupThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group.GroupConsolidatedAddResponse response =
                mock(Group.GroupConsolidatedAddResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4010");

        try (MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupConsolidatedAddRequest> ignored =
                     mockConstruction(Group.GroupConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            assertThatThrownBy(() -> tools.createGroup(
                    "sp-1", "grp-dup", "Support", "sp1.example.com", 50,
                    null, null, null,
                    null, null, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("create group");
        }
    }

    @Test
    void getGroupDoesNotElicitWhenBothIdsPresent() {
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

        try (MockedConstruction<ServiceProvider> ignored = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class)) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-9"))).thenReturn(group);

            tools.getGroup("sp-1", "grp-9", null, requestContext);

            verify(requestContext, never()).elicitEnabled();
            verify(requestContext, never()).elicit(any(), eq(GroupRef.class));
        }
    }

    @Test
    void getGroupUsesElicitedGroupRefWhenIdsMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(GroupRef.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new GroupRef("sp-1", "grp-9"), Map.of()));

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

        try (MockedConstruction<ServiceProvider> ignored = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class)) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-9"))).thenReturn(group);

            final GroupDetail detail = tools.getGroup(null, null, null, requestContext);

            assertThat(detail.groupId()).isEqualTo("grp-9");
            assertThat(detail.serviceProviderId()).isEqualTo("sp-1");
        }
    }

    @Test
    void getGroupThrowsWhenIdsMissingWithoutContext() {
        assertThatThrownBy(() -> tools.getGroup(null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("is required");
    }

    @Test
    void createGroupUsesElicitedRequiredFieldsWhenMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(CreateGroupDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new CreateGroupDetails("sp-1", "grp-new", "Support", "sp1.example.com", 50),
                        Map.of()));

        final Group created = mock(Group.class);
        when(created.getGroupId()).thenReturn("grp-new");
        when(created.getGroupName()).thenReturn("Support");
        when(created.getServiceProviderId()).thenReturn("sp-1");
        when(created.getDefaultDomain()).thenReturn("sp1.example.com");
        when(created.getUserCount()).thenReturn(0);
        when(created.getUserLimit()).thenReturn(50);
        when(created.getCallingLineIdName()).thenReturn(null);
        when(created.getCallingLineIdPhoneNumber()).thenReturn(null);
        when(created.getTimeZone()).thenReturn(null);
        when(created.getLocationDialingCode()).thenReturn(null);
        when(created.getContact()).thenReturn(null);
        when(created.getAddress()).thenReturn(null);

        final Group.GroupConsolidatedAddResponse response =
                mock(Group.GroupConsolidatedAddResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> ignored = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupConsolidatedAddRequest> reqCtor =
                     mockConstruction(Group.GroupConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-new")))
                    .thenReturn(created);

            final GroupDetail detail = tools.createGroup(
                    null, null, null, null, null,
                    null, null, null,
                    null, null, null,
                    null, null, null, null, null, null, null, requestContext);

            org.mockito.Mockito.verify(reqCtor.constructed().get(0)).setGroupId("grp-new");
            org.mockito.Mockito.verify(reqCtor.constructed().get(0)).setGroupName("Support");
            assertThat(detail.groupId()).isEqualTo("grp-new");
            assertThat(detail.serviceProviderId()).isEqualTo("sp-1");
        }
    }

    @Test
    void createGroupFailsWhenElicitationDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(CreateGroupDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() -> tools.createGroup(
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null, null, null, null, null, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("serviceProviderId, groupId, groupName, defaultDomain and userLimit are required");
    }

    @Test
    void createGroupThrowsWhenRequiredFieldsMissingWithoutContext() {
        assertThatThrownBy(() -> tools.createGroup(
                null, null, null, null, null,
                null, null, null,
                null, null, null,
                null, null, null, null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("is required");
    }

    @Test
    void deleteGroupReturnsConfirmation() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> ignored = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupDeleteRequest> mocked =
                     mockConstruction(Group.GroupDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            final String result = tools.deleteGroup("sp-1", "grp-1", true, null);

            assertThat(mocked.constructed()).hasSize(1);
            assertThat(result).contains("grp-1").contains("sp-1");
        }
    }

    @Test
    void deleteGroupRequiresAreYouSure() {
        assertThatThrownBy(() -> tools.deleteGroup("sp-1", "grp-1", null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure")
                .hasMessageContaining("areYouSure=true");
        assertThatThrownBy(() -> tools.deleteGroup("sp-1", "grp-1", false, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure");
        verify(connectionFactory, never()).connect(any(), any());
    }

    @Test
    void deleteGroupThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4008");

        try (MockedConstruction<ServiceProvider> ignored = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupDeleteRequest> deleteCtor =
                     mockConstruction(Group.GroupDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            groupStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            assertThatThrownBy(() -> tools.deleteGroup("sp-1", "grp-1", true, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("delete group");
            assertThat(deleteCtor.constructed()).hasSize(1);
        }
    }
}
