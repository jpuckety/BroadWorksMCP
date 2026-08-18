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
import co.pitayagroup.mcp.broadworks.mcp.model.ServicePackDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.ServicePackSummary;

import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.UnboundedNonNegativeInt;
import co.ecg.alpaca.toolkit.generated.datatypes.UnboundedPositiveInt;
import co.ecg.alpaca.toolkit.generated.enums.UserService;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServicePackUserServiceTableRow;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class ServicePackToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private ServicePackTools tools;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new ServicePackTools(connectionFactory);
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

    /** Builds a populated detail-list response mock for the read-back after create/modify/get. */
    private static ServiceProvider.ServiceProviderServicePackGetDetailListResponse detailResponse(
            String name, String description, Boolean available, Integer quantity, List<String> services) {
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse response =
                mock(ServiceProvider.ServiceProviderServicePackGetDetailListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getServicePackName()).thenReturn(name);
        when(response.getServicePackDescription()).thenReturn(description);
        when(response.getIsAvailableForUse()).thenReturn(available);
        if (quantity != null) {
            final UnboundedPositiveInt value = new UnboundedPositiveInt();
            value.setQuantity(quantity);
            when(response.getServicePackQuantity()).thenReturn(value);
        }
        // Build the rows first: nesting when(row.getService()) inside the thenReturn(...) argument would
        // corrupt the outer stubbing (Mockito UnfinishedStubbingException).
        final List<ServiceProviderServicePackUserServiceTableRow> rows =
                services.stream().map(ServicePackToolsTest::serviceRow).toList();
        when(response.getUserServiceTable()).thenReturn(rows);
        return response;
    }

    private static ServiceProviderServicePackUserServiceTableRow serviceRow(String service) {
        final ServiceProviderServicePackUserServiceTableRow row =
                mock(ServiceProviderServicePackUserServiceTableRow.class);
        when(row.getService()).thenReturn(service);
        return row;
    }

    @Test
    void listServicePacksMapsNamesToSummaries() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-1"))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServicePackGetListResponse response =
                mock(ServiceProvider.ServiceProviderServicePackGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getServicePackName()).thenReturn(new String[] {"Gold", "Silver"});

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final List<ServicePackSummary> result = tools.listServicePacks("sp-1", "res-1");

            assertThat(result).containsExactly(
                    new ServicePackSummary("Gold"), new ServicePackSummary("Silver"));
        }
    }

    @Test
    void listServicePacksReturnsEmptyWhenNamesNull() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServicePackGetListResponse response =
                mock(ServiceProvider.ServiceProviderServicePackGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getServicePackName()).thenReturn(null);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThat(tools.listServicePacks("sp-1", null)).isEmpty();
        }
    }

    @Test
    void listServicePacksThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServicePackGetListResponse response =
                mock(ServiceProvider.ServiceProviderServicePackGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4001");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThatThrownBy(() -> tools.listServicePacks("sp-1", null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("list service packs");
        }
    }

    @Test
    void listServicePacksRejectsMissingServiceProviderId() {
        assertThatThrownBy(() -> tools.listServicePacks("  ", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("serviceProviderId is required");
    }

    @Test
    void getServicePackMapsToDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse response =
                detailResponse("Gold", "Premium bundle", true, 25, List.of("Call Waiting"));
        final UnboundedNonNegativeInt assigned = new UnboundedNonNegativeInt();
        assigned.setQuantity(3);
        when(response.getAssignedQuantity()).thenReturn(assigned);
        final UnboundedPositiveInt allowed = new UnboundedPositiveInt();
        allowed.setFlagUnlimited();
        when(response.getAllowedQuantity()).thenReturn(allowed);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetDetailListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetDetailListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final ServicePackDetail detail = tools.getServicePack("sp-1", "Gold", null);

            assertThat(detail.servicePackName()).isEqualTo("Gold");
            assertThat(detail.description()).isEqualTo("Premium bundle");
            assertThat(detail.availableForUse()).isTrue();
            assertThat(detail.quantity().quantity()).isEqualTo(25);
            assertThat(detail.quantity().unlimited()).isFalse();
            assertThat(detail.assignedQuantity()).isEqualTo(3);
            assertThat(detail.allowedQuantity().unlimited()).isTrue();
            assertThat(detail.userServices()).containsExactly("Call Waiting");
        }
    }

    @Test
    void getServicePackRejectsMissingServicePackName() {
        assertThatThrownBy(() -> tools.getServicePack("sp-1", " ", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("servicePackName is required");
    }

    @Test
    void createServicePackSendsFieldsAndReturnsDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse addResponse = mock(DefaultResponse.class);
        when(addResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse detail =
                detailResponse("Gold", "desc", true, 10, List.of("Call Waiting"));

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackAddRequest> addMock =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(addResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetDetailListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetDetailListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(detail))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final ServicePackDetail result = tools.createServicePack(
                    "sp-1", "Gold", "desc", true, 10, null, List.of("Call Waiting"), null);

            final ServiceProvider.ServiceProviderServicePackAddRequest req = addMock.constructed().get(0);
            verify(req).setServicePackName("Gold");
            verify(req).setServicePackDescription("desc");
            verify(req).setIsAvailableForUse(true);

            final ArgumentCaptor<UnboundedPositiveInt> qtyCaptor =
                    ArgumentCaptor.forClass(UnboundedPositiveInt.class);
            verify(req).setServicePackQuantity(qtyCaptor.capture());
            assertThat(qtyCaptor.getValue().getQuantity()).isEqualTo(10);

            final ArgumentCaptor<UserService[]> serviceCaptor = ArgumentCaptor.forClass(UserService[].class);
            verify(req).setServiceName(serviceCaptor.capture());
            assertThat(serviceCaptor.getValue()).containsExactly(UserService.get("Call Waiting"));

            assertThat(result.servicePackName()).isEqualTo("Gold");
            assertThat(result.userServices()).containsExactly("Call Waiting");
        }
    }

    @Test
    void createServicePackSupportsUnlimitedQuantity() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse addResponse = mock(DefaultResponse.class);
        when(addResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse detail =
                detailResponse("Gold", null, null, null, List.of());

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackAddRequest> addMock =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(addResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetDetailListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetDetailListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(detail))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            tools.createServicePack("sp-1", "Gold", null, null, null, true, null, null);

            final ServiceProvider.ServiceProviderServicePackAddRequest req = addMock.constructed().get(0);
            final ArgumentCaptor<UnboundedPositiveInt> qtyCaptor =
                    ArgumentCaptor.forClass(UnboundedPositiveInt.class);
            verify(req).setServicePackQuantity(qtyCaptor.capture());
            assertThat(qtyCaptor.getValue().getUnlimited()).isTrue();
            verify(req, never()).setServiceName(any(UserService[].class));
        }
    }

    @Test
    void createServicePackRejectsUnknownService() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        assertThatThrownBy(() -> tools.createServicePack(
                "sp-1", "Gold", null, null, 5, null, List.of("Not A Real Service"), null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Unknown user service");
    }

    @Test
    void createServicePackRejectsMissingServicePackName() {
        assertThatThrownBy(() -> tools.createServicePack(
                "sp-1", "  ", null, null, 5, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("servicePackName is required");
    }

    @Test
    void createServicePackThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse addResponse = mock(DefaultResponse.class);
        when(addResponse.isErrorResponse()).thenReturn(true);
        when(addResponse.getErrorCode()).thenReturn("4003");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackAddRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(addResponse))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThatThrownBy(() -> tools.createServicePack(
                    "sp-1", "Gold", null, null, 5, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("create service pack");
        }
    }

    @Test
    void modifyServicePackAppliesInPlaceFieldsOnlyWithoutAddingServices() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse detail =
                detailResponse("Platinum", "new desc", false, 50, List.of("Call Waiting"));

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackModifyRequest> modifyMock =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServicePackAddServiceListRequest> addServiceMock =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackAddServiceListRequest.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetDetailListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetDetailListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(detail))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final ServicePackDetail result = tools.modifyServicePack(
                    "sp-1", "Gold", "Platinum", "new desc", false, 50, null, null, null);

            final ServiceProvider.ServiceProviderServicePackModifyRequest req = modifyMock.constructed().get(0);
            verify(req).setNewServicePackName("Platinum");
            verify(req).setServicePackDescription("new desc");
            verify(req).setIsAvailableForUse(false);
            final ArgumentCaptor<UnboundedPositiveInt> qtyCaptor =
                    ArgumentCaptor.forClass(UnboundedPositiveInt.class);
            verify(req).setServicePackQuantity(qtyCaptor.capture());
            assertThat(qtyCaptor.getValue().getQuantity()).isEqualTo(50);

            // No add-service request is fired when addServices is omitted (add-only, no removal path).
            assertThat(addServiceMock.constructed()).isEmpty();

            assertThat(result.servicePackName()).isEqualTo("Platinum");
        }
    }

    @Test
    void modifyServicePackClearsDescriptionWithEmptyString() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse detail =
                detailResponse("Gold", null, true, 10, List.of());

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackModifyRequest> modifyMock =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetDetailListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetDetailListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(detail))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            tools.modifyServicePack("sp-1", "Gold", null, "", null, null, null, null, null);

            final ServiceProvider.ServiceProviderServicePackModifyRequest req = modifyMock.constructed().get(0);
            verify(req).setServicePackDescription(null);
            verify(req, never()).setNewServicePackName(any());
            verify(req, never()).setIsAvailableForUse(any());
            verify(req, never()).setServicePackQuantity(any());
        }
    }

    @Test
    void modifyServicePackAddServicesFiresSeparateRequest() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(false);
        final DefaultResponse addResponse = mock(DefaultResponse.class);
        when(addResponse.isErrorResponse()).thenReturn(false);
        final ServiceProvider.ServiceProviderServicePackGetDetailListResponse detail =
                detailResponse("Gold", null, true, 10, List.of("Call Waiting"));

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackModifyRequest> ignoredModify =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServicePackAddServiceListRequest> addServiceMock =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackAddServiceListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(addResponse));
             MockedConstruction<ServiceProvider.ServiceProviderServicePackGetDetailListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackGetDetailListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(detail))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            tools.modifyServicePack("sp-1", "Gold", null, null, null, null, null,
                    List.of("Call Waiting"), null);

            assertThat(addServiceMock.constructed()).hasSize(1);
            verify(addServiceMock.constructed().get(0)).fire();
        }
    }

    @Test
    void modifyServicePackThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse modifyResponse = mock(DefaultResponse.class);
        when(modifyResponse.isErrorResponse()).thenReturn(true);
        when(modifyResponse.getErrorCode()).thenReturn("4010");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackModifyRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(modifyResponse))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThatThrownBy(() -> tools.modifyServicePack(
                    "sp-1", "Gold", "Platinum", null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("modify service pack");
        }
    }

    @Test
    void deleteServicePackReturnsConfirmation() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackDeleteRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final String result = tools.deleteServicePack("sp-1", "Gold", null);

            assertThat(mocked.constructed()).hasSize(1);
            assertThat(result).contains("Gold").contains("sp-1");
        }
    }

    @Test
    void deleteServicePackThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4008");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderServicePackDeleteRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderServicePackDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThatThrownBy(() -> tools.deleteServicePack("sp-1", "Gold", null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("delete service pack");
        }
    }

    @Test
    void failsWhenNoAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> tools.listServicePacks("sp-1", null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No authenticated user");
    }
}
