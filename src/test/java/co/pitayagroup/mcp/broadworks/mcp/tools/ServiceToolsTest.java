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

import java.util.List;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.AssignedService;
import co.pitayagroup.mcp.broadworks.mcp.model.AssignedServicesResult;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceAuthorization;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceAuthorizationSet;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceQuantity;

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
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.ServiceProviderId;

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
class ServiceToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private ServiceTools tools;

    @Mock
    private McpSyncRequestContext requestContext;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new ServiceTools(connectionFactory);
        authenticateAs("sub-1", "user@example.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject, String email) {
        final var principal = new DefaultOAuth2AuthenticatedPrincipal(subject,
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, subject, UserInfo.EMAIL_ATTRIBUTE, email),
                List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", List.of()));
    }

    // ---- row builders -------------------------------------------------------------------------

    private static ServiceProviderServiceUserServicesAuthorizationTableRow spUserRow(
            String name, String authorized, String limited, String quantity) {
        final ServiceProviderServiceUserServicesAuthorizationTableRow row =
                mock(ServiceProviderServiceUserServicesAuthorizationTableRow.class);
        when(row.getServiceName()).thenReturn(name);
        when(row.getAuthorized()).thenReturn(authorized);
        when(row.getLimited()).thenReturn(limited);
        when(row.getQuantity()).thenReturn(quantity);
        return row;
    }

    private static ServiceProviderServiceGroupServicesAuthorizationTableRow spGroupRow(
            String name, String authorized, String limited, String quantity) {
        final ServiceProviderServiceGroupServicesAuthorizationTableRow row =
                mock(ServiceProviderServiceGroupServicesAuthorizationTableRow.class);
        when(row.getServiceName()).thenReturn(name);
        when(row.getAuthorized()).thenReturn(authorized);
        when(row.getLimited()).thenReturn(limited);
        when(row.getQuantity()).thenReturn(quantity);
        return row;
    }

    private static GroupServiceUserServicesAuthorizationTableRow grpUserRow(
            String name, String authorized, String limited, String quantity) {
        final GroupServiceUserServicesAuthorizationTableRow row =
                mock(GroupServiceUserServicesAuthorizationTableRow.class);
        when(row.getServiceName()).thenReturn(name);
        when(row.getAuthorized()).thenReturn(authorized);
        when(row.getLimited()).thenReturn(limited);
        when(row.getQuantity()).thenReturn(quantity);
        return row;
    }

    private static GroupServiceGroupServicesAuthorizationTableRow grpGroupRow(
            String name, String authorized, String limited, String quantity) {
        final GroupServiceGroupServicesAuthorizationTableRow row =
                mock(GroupServiceGroupServicesAuthorizationTableRow.class);
        when(row.getServiceName()).thenReturn(name);
        when(row.getAuthorized()).thenReturn(authorized);
        when(row.getLimited()).thenReturn(limited);
        when(row.getQuantity()).thenReturn(quantity);
        return row;
    }

    private static GroupServiceServicePacksAuthorizationTableRow grpPackRow(
            String name, String authorized, String limited, String allowed) {
        final GroupServiceServicePacksAuthorizationTableRow row =
                mock(GroupServiceServicePacksAuthorizationTableRow.class);
        when(row.getServicePackName()).thenReturn(name);
        when(row.getAuthorized()).thenReturn(authorized);
        when(row.getLimited()).thenReturn(limited);
        when(row.getAllowed()).thenReturn(allowed);
        return row;
    }

    // ---- service provider read ----------------------------------------------------------------

    @Test
    void getServiceProviderAuthorizationMapsUserAndGroupTables() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-1"))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse response =
                mock(ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        // Build the row mocks into locals first: nesting a when(row...) stub inside the thenReturn(...)
        // argument corrupts the outer stubbing (Mockito UnfinishedStubbingException).
        final List<ServiceProviderServiceUserServicesAuthorizationTableRow> userRows = List.of(
                spUserRow("Call Waiting", "true", "true", "5"),
                spUserRow("Voice Messaging User", "false", null, null));
        final List<ServiceProviderServiceGroupServicesAuthorizationTableRow> groupRows = List.of(
                spGroupRow("Auto Attendant", "true", "false", null));
        when(response.getUserServicesAuthorizationTable()).thenReturn(userRows);
        when(response.getGroupServicesAuthorizationTable()).thenReturn(groupRows);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final ServiceAuthorizationSet result =
                    tools.getServiceProviderServiceAuthorization("sp-1", "res-1");

            assertThat(result.servicePacks()).isEmpty();
            assertThat(result.userServices()).containsExactly(
                    new ServiceAuthorization("Call Waiting", true, new ServiceQuantity(5, false)),
                    new ServiceAuthorization("Voice Messaging User", false, null));
            assertThat(result.groupServices()).containsExactly(
                    new ServiceAuthorization("Auto Attendant", true, new ServiceQuantity(null, true)));
        }
    }

    @Test
    void getServiceProviderAuthorizationRejectsMissingServiceProviderId() {
        assertThatThrownBy(() -> tools.getServiceProviderServiceAuthorization("  ", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("serviceProviderId is required");
    }

    @Test
    void getServiceProviderAuthorizationThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse response =
                mock(ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4001");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThatThrownBy(() -> tools.getServiceProviderServiceAuthorization("sp-1", null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("get service authorization");
        }
    }

    // ---- service provider modify --------------------------------------------------------------

    @Test
    void modifyServiceProviderAuthorizationSendsOnlySuppliedEntries() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse readResponse =
                mock(ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse.class);
        when(readResponse.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest> modifyMock =
                     mockConstruction(ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(readResponse))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final List<ServiceAuthorization> userEntries = List.of(
                    new ServiceAuthorization("Call Waiting", true, new ServiceQuantity(7, false)),
                    new ServiceAuthorization("Voice Messaging User", false, null));

            tools.modifyServiceProviderServiceAuthorization("sp-1", userEntries, null, null);

            final ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest req =
                    modifyMock.constructed().get(0);

            final ArgumentCaptor<UserServiceAuthorization[]> userCaptor =
                    ArgumentCaptor.forClass(UserServiceAuthorization[].class);
            verify(req).setUserServiceAuthorization(userCaptor.capture());
            final UserServiceAuthorization[] sent = userCaptor.getValue();
            assertThat(sent).hasSize(2);
            assertThat(sent[0].getServiceName()).isEqualTo(UserService.get("Call Waiting"));
            assertThat(sent[0].getAuthorizedQuantity().getQuantity()).isEqualTo(7);
            assertThat(sent[0].unauthorizedFlagExist()).isFalse();
            assertThat(sent[1].getServiceName()).isEqualTo(UserService.get("Voice Messaging User"));
            assertThat(sent[1].unauthorizedFlagExist()).isTrue();

            // groupServices omitted -> the group setter is never called (partial update).
            verify(req, never()).setGroupServiceAuthorization(any());
        }
    }

    @Test
    void modifyServiceProviderAuthorizationSupportsUnlimitedQuantity() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse readResponse =
                mock(ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse.class);
        when(readResponse.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest> modifyMock =
                     mockConstruction(ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(readResponse))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final List<ServiceAuthorization> groupEntries = List.of(
                    new ServiceAuthorization("Auto Attendant", true, new ServiceQuantity(null, true)));

            tools.modifyServiceProviderServiceAuthorization("sp-1", null, groupEntries, null);

            final ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest req =
                    modifyMock.constructed().get(0);

            final ArgumentCaptor<GroupServiceAuthorization[]> groupCaptor =
                    ArgumentCaptor.forClass(GroupServiceAuthorization[].class);
            verify(req).setGroupServiceAuthorization(groupCaptor.capture());
            final GroupServiceAuthorization[] sent = groupCaptor.getValue();
            assertThat(sent).hasSize(1);
            assertThat(sent[0].getServiceName()).isEqualTo(GroupService.get("Auto Attendant"));
            assertThat(sent[0].getAuthorizedQuantity().getUnlimited()).isTrue();

            verify(req, never()).setUserServiceAuthorization(any());
        }
    }

    @Test
    void modifyServiceProviderAuthorizationRejectsWhenNothingSupplied() {
        assertThatThrownBy(() -> tools.modifyServiceProviderServiceAuthorization("sp-1", null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    void modifyServiceProviderAuthorizationRejectsUnknownService() {
        final List<ServiceAuthorization> userEntries =
                List.of(new ServiceAuthorization("Not A Real Service", true, null));
        assertThatThrownBy(() ->
                tools.modifyServiceProviderServiceAuthorization("sp-1", userEntries, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Unknown user service");
    }

    @Test
    void modifyServiceProviderAuthorizationThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(true);
        when(modifyResponse.getErrorCode()).thenReturn("4003");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceModifyAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final List<ServiceAuthorization> userEntries =
                    List.of(new ServiceAuthorization("Call Waiting", true, new ServiceQuantity(1, false)));

            assertThatThrownBy(() ->
                    tools.modifyServiceProviderServiceAuthorization("sp-1", userEntries, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("modify service authorization");
        }
    }

    // ---- group read ---------------------------------------------------------------------------

    @Test
    void getGroupAuthorizationMapsPackGroupAndUserTables() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final Group.GroupServiceGetAuthorizationListResponse response =
                mock(Group.GroupServiceGetAuthorizationListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        // Build the row mocks into locals first (see note above) to avoid nested stubbing.
        final List<GroupServiceServicePacksAuthorizationTableRow> packRows = List.of(
                grpPackRow("Gold", "true", "true", "12"));
        final List<GroupServiceGroupServicesAuthorizationTableRow> groupRows = List.of(
                grpGroupRow("Auto Attendant", "true", "false", null));
        final List<GroupServiceUserServicesAuthorizationTableRow> userRows = List.of(
                grpUserRow("Call Waiting", "true", "true", "3"),
                grpUserRow("Voice Messaging User", "false", null, null));
        when(response.getServicePacksAuthorizationTable()).thenReturn(packRows);
        when(response.getGroupServicesAuthorizationTable()).thenReturn(groupRows);
        when(response.getUserServicesAuthorizationTable()).thenReturn(userRows);

        try (MockedConstruction<ServiceProvider> ignoredSp = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> grpStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(Group.GroupServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            grpStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            final ServiceAuthorizationSet result =
                    tools.getGroupServiceAuthorization("sp-1", "grp-1", null);

            assertThat(result.servicePacks()).containsExactly(
                    new ServiceAuthorization("Gold", true, new ServiceQuantity(12, false)));
            assertThat(result.groupServices()).containsExactly(
                    new ServiceAuthorization("Auto Attendant", true, new ServiceQuantity(null, true)));
            assertThat(result.userServices()).containsExactly(
                    new ServiceAuthorization("Call Waiting", true, new ServiceQuantity(3, false)),
                    new ServiceAuthorization("Voice Messaging User", false, null));
        }
    }

    @Test
    void getGroupAuthorizationRejectsMissingGroupId() {
        assertThatThrownBy(() -> tools.getGroupServiceAuthorization("sp-1", "  ", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("groupId is required");
    }

    // ---- group modify -------------------------------------------------------------------------

    @Test
    void modifyGroupAuthorizationSendsSuppliedPackAndUserEntriesOnly() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(false);
        final Group.GroupServiceGetAuthorizationListResponse readResponse =
                mock(Group.GroupServiceGetAuthorizationListResponse.class);
        when(readResponse.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> ignoredSp = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> grpStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupServiceModifyAuthorizationListRequest> modifyMock =
                     mockConstruction(Group.GroupServiceModifyAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse));
             MockedConstruction<Group.GroupServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(Group.GroupServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(readResponse))) {
            grpStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            final List<ServiceAuthorization> userEntries =
                    List.of(new ServiceAuthorization("Call Waiting", true, new ServiceQuantity(2, false)));
            final List<ServiceAuthorization> packEntries = List.of(
                    new ServiceAuthorization("Gold", true, new ServiceQuantity(null, true)),
                    new ServiceAuthorization("Silver", false, null));

            tools.modifyGroupServiceAuthorization("sp-1", "grp-1", userEntries, null, packEntries, null);

            final Group.GroupServiceModifyAuthorizationListRequest req = modifyMock.constructed().get(0);

            final ArgumentCaptor<UserServiceAuthorization[]> userCaptor =
                    ArgumentCaptor.forClass(UserServiceAuthorization[].class);
            verify(req).setUserServiceAuthorization(userCaptor.capture());
            assertThat(userCaptor.getValue()).hasSize(1);
            assertThat(userCaptor.getValue()[0].getServiceName()).isEqualTo(UserService.get("Call Waiting"));
            assertThat(userCaptor.getValue()[0].getAuthorizedQuantity().getQuantity()).isEqualTo(2);

            final ArgumentCaptor<ServicePackAuthorization[]> packCaptor =
                    ArgumentCaptor.forClass(ServicePackAuthorization[].class);
            verify(req).setServicePackAuthorization(packCaptor.capture());
            final ServicePackAuthorization[] packs = packCaptor.getValue();
            assertThat(packs).hasSize(2);
            assertThat(packs[0].getServicePackName()).isEqualTo("Gold");
            assertThat(packs[0].getAuthorizedQuantity().getUnlimited()).isTrue();
            assertThat(packs[1].getServicePackName()).isEqualTo("Silver");
            assertThat(packs[1].unauthorizedFlagExist()).isTrue();

            // groupServices omitted -> the group setter is never called.
            verify(req, never()).setGroupServiceAuthorization(any());
        }
    }

    @Test
    void modifyGroupAuthorizationRejectsWhenNothingSupplied() {
        assertThatThrownBy(() ->
                tools.modifyGroupServiceAuthorization("sp-1", "grp-1", null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    void modifyGroupAuthorizationThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(true);
        when(modifyResponse.getErrorCode()).thenReturn("4009");

        try (MockedConstruction<ServiceProvider> ignoredSp = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> grpStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupServiceModifyAuthorizationListRequest> ignored =
                     mockConstruction(Group.GroupServiceModifyAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse))) {
            grpStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            final List<ServiceAuthorization> groupEntries =
                    List.of(new ServiceAuthorization("Auto Attendant", true, new ServiceQuantity(1, false)));

            assertThatThrownBy(() ->
                    tools.modifyGroupServiceAuthorization("sp-1", "grp-1", null, groupEntries, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("modify service authorization");
        }
    }

    // ---- group service assign / unassign ------------------------------------------------------

    @Test
    void assignGroupServicesBuildsRequestWithParsedServices() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> ignoredSp = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> grpStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupServiceAssignListRequest> assignMock =
                     mockConstruction(Group.GroupServiceAssignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            grpStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            final List<String> result =
                    tools.assignGroupServices("sp-1", "grp-1", List.of("Auto Attendant"), null);

            assertThat(result).containsExactly("Auto Attendant");

            final Group.GroupServiceAssignListRequest req = assignMock.constructed().get(0);
            final ArgumentCaptor<GroupService[]> captor = ArgumentCaptor.forClass(GroupService[].class);
            verify(req).setServiceName(captor.capture());
            assertThat(captor.getValue()).containsExactly(GroupService.get("Auto Attendant"));
        }
    }

    @Test
    void unassignGroupServicesBuildsRequestWithParsedServices() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedConstruction<ServiceProvider> ignoredSp = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> grpStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupServiceUnassignListRequest> unassignMock =
                     mockConstruction(Group.GroupServiceUnassignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            grpStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            final List<String> result =
                    tools.unassignGroupServices("sp-1", "grp-1", List.of("Auto Attendant"), null);

            assertThat(result).containsExactly("Auto Attendant");

            final Group.GroupServiceUnassignListRequest req = unassignMock.constructed().get(0);
            final ArgumentCaptor<GroupService[]> captor = ArgumentCaptor.forClass(GroupService[].class);
            verify(req).setServiceName(captor.capture());
            assertThat(captor.getValue()).containsExactly(GroupService.get("Auto Attendant"));
        }
    }

    @Test
    void assignGroupServicesRejectsMissingServiceNames() {
        assertThatThrownBy(() -> tools.assignGroupServices("sp-1", "grp-1", List.of(), null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("At least one group service");
    }

    @Test
    void assignGroupServicesRejectsUnknownService() {
        assertThatThrownBy(() ->
                tools.assignGroupServices("sp-1", "grp-1", List.of("Not A Real Service"), null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Unknown group service");
    }

    @Test
    void assignGroupServicesThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final Group group = mock(Group.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4200");

        try (MockedConstruction<ServiceProvider> ignoredSp = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> grpStatics = mockStatic(Group.class);
             MockedConstruction<Group.GroupServiceAssignListRequest> ignored =
                     mockConstruction(Group.GroupServiceAssignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            grpStatics.when(() -> Group.getPopulatedGroup(any(), eq("grp-1"))).thenReturn(group);

            assertThatThrownBy(() ->
                    tools.assignGroupServices("sp-1", "grp-1", List.of("Auto Attendant"), null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("assign group services");
        }
    }

    // ---- user assigned services read ----------------------------------------------------------

    @Test
    void getUserAssignedServicesMapsGroupAndUserEntries() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final User.UserAssignedServicesGetListResponse response =
                mock(User.UserAssignedServicesGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        // Build the entry mocks into locals first to avoid nested-stubbing corruption.
        final AssignedGroupServicesEntry groupEntry = mock(AssignedGroupServicesEntry.class);
        when(groupEntry.getServiceName()).thenReturn(GroupService.get("Auto Attendant"));
        when(groupEntry.getIsActive()).thenReturn(true);
        final AssignedUserServicesEntry userEntry = mock(AssignedUserServicesEntry.class);
        when(userEntry.getServiceName()).thenReturn(UserService.get("Call Waiting"));
        when(userEntry.getIsActive()).thenReturn(false);
        when(response.getGroupServiceEntry())
                .thenReturn(new AssignedGroupServicesEntry[]{groupEntry});
        when(response.getUserServiceEntry())
                .thenReturn(new AssignedUserServicesEntry[]{userEntry});

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserAssignedServicesGetListRequest> ignored =
                     mockConstruction(User.UserAssignedServicesGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1"))).thenReturn(user);

            final AssignedServicesResult result = tools.getUserAssignedServices("user-1", null);

            assertThat(result.groupServices())
                    .containsExactly(new AssignedService("Auto Attendant", true));
            assertThat(result.userServices())
                    .containsExactly(new AssignedService("Call Waiting", false));
        }
    }

    @Test
    void getUserAssignedServicesRejectsMissingUserId() {
        assertThatThrownBy(() -> tools.getUserAssignedServices("  ", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("userId is required");
    }

    // ---- user service assign / unassign -------------------------------------------------------

    @Test
    void assignUserServicesBuildsRequestWithServicesAndPacks() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final DefaultResponse assignResponse = mock(DefaultResponse.class);
        when(assignResponse.isErrorResponse()).thenReturn(false);
        final User.UserAssignedServicesGetListResponse readResponse =
                mock(User.UserAssignedServicesGetListResponse.class);
        when(readResponse.isErrorResponse()).thenReturn(false);

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserServiceAssignListRequest> assignMock =
                     mockConstruction(User.UserServiceAssignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(assignResponse));
             MockedConstruction<User.UserAssignedServicesGetListRequest> ignored =
                     mockConstruction(User.UserAssignedServicesGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(readResponse))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1"))).thenReturn(user);

            tools.assignUserServices("user-1", List.of("Call Waiting"), List.of("Gold"), null);

            final User.UserServiceAssignListRequest req = assignMock.constructed().get(0);
            final ArgumentCaptor<UserService[]> svcCaptor = ArgumentCaptor.forClass(UserService[].class);
            verify(req).setServiceName(svcCaptor.capture());
            assertThat(svcCaptor.getValue()).containsExactly(UserService.get("Call Waiting"));
            final ArgumentCaptor<String[]> packCaptor = ArgumentCaptor.forClass(String[].class);
            verify(req).setServicePackName(packCaptor.capture());
            assertThat(packCaptor.getValue()).containsExactly("Gold");
        }
    }

    @Test
    void assignUserServicesWithOnlyPacksSkipsServiceSetter() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final DefaultResponse assignResponse = mock(DefaultResponse.class);
        when(assignResponse.isErrorResponse()).thenReturn(false);
        final User.UserAssignedServicesGetListResponse readResponse =
                mock(User.UserAssignedServicesGetListResponse.class);
        when(readResponse.isErrorResponse()).thenReturn(false);

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserServiceAssignListRequest> assignMock =
                     mockConstruction(User.UserServiceAssignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(assignResponse));
             MockedConstruction<User.UserAssignedServicesGetListRequest> ignored =
                     mockConstruction(User.UserAssignedServicesGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(readResponse))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1"))).thenReturn(user);

            tools.assignUserServices("user-1", null, List.of("Gold"), null);

            final User.UserServiceAssignListRequest req = assignMock.constructed().get(0);
            final ArgumentCaptor<String[]> packCaptor = ArgumentCaptor.forClass(String[].class);
            verify(req).setServicePackName(packCaptor.capture());
            assertThat(packCaptor.getValue()).containsExactly("Gold");
            verify(req, never()).setServiceName(any());
        }
    }

    @Test
    void unassignUserServicesBuildsRequestWithServicesAndPacks() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final DefaultResponse unassignResponse = mock(DefaultResponse.class);
        when(unassignResponse.isErrorResponse()).thenReturn(false);
        final User.UserAssignedServicesGetListResponse readResponse =
                mock(User.UserAssignedServicesGetListResponse.class);
        when(readResponse.isErrorResponse()).thenReturn(false);

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserServiceUnassignListRequest> unassignMock =
                     mockConstruction(User.UserServiceUnassignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(unassignResponse));
             MockedConstruction<User.UserAssignedServicesGetListRequest> ignored =
                     mockConstruction(User.UserAssignedServicesGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(readResponse))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1"))).thenReturn(user);

            tools.unassignUserServices("user-1", List.of("Call Waiting"), List.of("Gold"), null);

            final User.UserServiceUnassignListRequest req = unassignMock.constructed().get(0);
            final ArgumentCaptor<UserService[]> svcCaptor = ArgumentCaptor.forClass(UserService[].class);
            verify(req).setServiceName(svcCaptor.capture());
            assertThat(svcCaptor.getValue()).containsExactly(UserService.get("Call Waiting"));
            final ArgumentCaptor<String[]> packCaptor = ArgumentCaptor.forClass(String[].class);
            verify(req).setServicePackName(packCaptor.capture());
            assertThat(packCaptor.getValue()).containsExactly("Gold");
        }
    }

    @Test
    void assignUserServicesRejectsWhenNothingSupplied() {
        assertThatThrownBy(() -> tools.assignUserServices("user-1", null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("At least one");
    }

    @Test
    void assignUserServicesRejectsUnknownService() {
        assertThatThrownBy(() ->
                tools.assignUserServices("user-1", List.of("Not A Real Service"), null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Unknown user service");
    }

    @Test
    void assignUserServicesThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final User user = mock(User.class);
        final DefaultResponse assignResponse = mock(DefaultResponse.class);
        when(assignResponse.isErrorResponse()).thenReturn(true);
        when(assignResponse.getErrorCode()).thenReturn("4210");

        try (MockedStatic<User> userStatics = mockStatic(User.class);
             MockedConstruction<User.UserServiceAssignListRequest> ignored =
                     mockConstruction(User.UserServiceAssignListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(assignResponse))) {
            userStatics.when(() -> User.getPopulatedUser(any(), eq("user-1"))).thenReturn(user);

            assertThatThrownBy(() ->
                    tools.assignUserServices("user-1", List.of("Call Waiting"), null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("assign services to user");
        }
    }

    @Test
    void failsWhenNoAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> tools.getServiceProviderServiceAuthorization("sp-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No authenticated user");
    }

    @Test
    void getServiceProviderAuthorizationDoesNotElicitWhenIdPresent() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-1"))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse response =
                mock(ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getUserServicesAuthorizationTable()).thenReturn(List.of());
        when(response.getGroupServicesAuthorizationTable()).thenReturn(List.of());

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            tools.getServiceProviderServiceAuthorization("sp-1", "res-1", requestContext);

            verify(requestContext, never()).elicitEnabled();
            verify(requestContext, never()).elicit(any(), eq(ServiceProviderId.class));
        }
    }

    @Test
    void getServiceProviderAuthorizationUsesElicitedIdWhenMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ServiceProviderId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ServiceProviderId("sp-1"), Map.of()));

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse response =
                mock(ServiceProvider.ServiceProviderServiceGetAuthorizationListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        final List<ServiceProviderServiceUserServicesAuthorizationTableRow> userRows =
                List.of(spUserRow("Call Waiting", "true", "true", "5"));
        when(response.getUserServicesAuthorizationTable()).thenReturn(userRows);
        when(response.getGroupServicesAuthorizationTable()).thenReturn(List.of());

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServiceGetAuthorizationListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final ServiceAuthorizationSet result =
                    tools.getServiceProviderServiceAuthorization(null, null, requestContext);

            assertThat(result.userServices()).containsExactly(
                    new ServiceAuthorization("Call Waiting", true, new ServiceQuantity(5, false)));
        }
    }

    @Test
    void getServiceProviderAuthorizationFailsWhenElicitationDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ServiceProviderId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() -> tools.getServiceProviderServiceAuthorization(null, null, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("serviceProviderId is required");
    }
}
