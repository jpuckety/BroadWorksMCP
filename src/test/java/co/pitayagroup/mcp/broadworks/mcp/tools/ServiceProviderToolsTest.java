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
import co.pitayagroup.mcp.broadworks.mcp.model.Page;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceProviderDetail;
import co.pitayagroup.mcp.broadworks.mcp.model.ServiceProviderSummary;
import co.pitayagroup.mcp.broadworks.mcp.util.Paging;

import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.generated.datatypes.Contact;
import co.ecg.alpaca.toolkit.generated.datatypes.StreetAddress;
import co.ecg.alpaca.toolkit.generated.tables.ServiceProviderServiceProviderTableRow;
import co.ecg.alpaca.toolkit.messaging.response.DefaultResponse;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceProviderTools.CreateServiceProviderDetails;
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
class ServiceProviderToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private ServiceProviderTools tools;

    @Mock
    private McpSyncRequestContext requestContext;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new ServiceProviderTools(connectionFactory, new ConfirmationService(
                new InMemoryPendingApprovalStore(), new PublicBaseUrlProperties("")));
        authenticateAs("sub-1", "user@example.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject, String email) {
        final var principal = new DefaultOAuth2AuthenticatedPrincipal(subject,
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, subject, UserInfo.EMAIL_ATTRIBUTE, email),
                java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", java.util.List.of()));
    }

    @Test
    void listServiceProvidersMapsRowsToDtosAndUsesTenantConnection() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-1"))).thenReturn(null);

        final ServiceProviderServiceProviderTableRow row = mock(ServiceProviderServiceProviderTableRow.class);
        when(row.getServiceProviderId()).thenReturn("sp-1");
        when(row.getServiceProviderName()).thenReturn("Acme");
        when(row.getIsEnterprise()).thenReturn("true");
        when(row.getResellerId()).thenReturn("res-x");

        final ServiceProvider.ServiceProviderGetListResponse response =
                mock(ServiceProvider.ServiceProviderGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getServiceProviderTable()).thenReturn(List.of(row));

        try (MockedConstruction<ServiceProvider.ServiceProviderGetListRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listServiceProviders(null, null, null, null, "res-1");

            assertThat(result.schema())
                    .containsExactly("serviceProviderId", "serviceProviderName", "enterprise", "resellerId");
            assertThat(result.rows()).containsExactly(Arrays.asList("sp-1", "Acme", true, "res-x"));
            assertThat(result.returned()).isEqualTo(1);
            assertThat(result.totalMatching()).isEqualTo(1);
            assertThat(result.hasMore()).isFalse();
            assertThat(result.nextCursor()).isNull();
            assertThat(result.truncationReason()).isNull();
            assertThat(result.suggestion()).isNotBlank();
            assertThat(mocked.constructed()).hasSize(1);
        }
    }

    @Test
    void listServiceProvidersReturnsEmptyPageWhenTableIsNull() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider.ServiceProviderGetListResponse response =
                mock(ServiceProvider.ServiceProviderGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getServiceProviderTable()).thenReturn(null);

        try (MockedConstruction<ServiceProvider.ServiceProviderGetListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            final Page result = tools.listServiceProviders(null, null, null, null, null);

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
    void listServiceProvidersThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider.ServiceProviderGetListResponse response =
                mock(ServiceProvider.ServiceProviderGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4001");

        try (MockedConstruction<ServiceProvider.ServiceProviderGetListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            assertThatThrownBy(() -> tools.listServiceProviders(null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("list service providers");
        }
    }

    @Test
    void getServiceProviderMapsToDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        when(sp.getServiceProviderId()).thenReturn("sp-9");
        when(sp.getServiceProviderName()).thenReturn("Globex");
        when(sp.getDefaultDomain()).thenReturn("globex.example.com");
        when(sp.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(sp.getResellerId()).thenReturn(null);
        when(sp.getSupportEmail()).thenReturn("support@globex.example.com");

        final Contact contact = mock(Contact.class);
        when(contact.getContactName()).thenReturn(Optional.of("Jane Doe"));
        when(contact.getContactNumber()).thenReturn(Optional.of("+1-555-0100"));
        when(contact.getContactEmail()).thenReturn(Optional.of("jane@globex.example.com"));
        when(sp.getContact()).thenReturn(contact);

        final StreetAddress address = mock(StreetAddress.class);
        when(address.getAddressLine1()).thenReturn(Optional.of("1 Main St"));
        when(address.getAddressLine2()).thenReturn(Optional.of("Suite 200"));
        when(address.getCity()).thenReturn(Optional.of("Springfield"));
        when(address.getStateOrProvince()).thenReturn(Optional.of("IL"));
        when(address.getZipOrPostalCode()).thenReturn(Optional.of("62701"));
        when(address.getCountry()).thenReturn(Optional.of("US"));
        when(sp.getAddress()).thenReturn(address);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9"))).thenReturn(sp);

            final ServiceProviderDetail detail = tools.getServiceProvider("sp-9", null);

            assertThat(detail).isEqualTo(new ServiceProviderDetail(
                    "sp-9", "Globex", "globex.example.com", false, null,
                    "support@globex.example.com",
                    new ContactInfo("Jane Doe", "+1-555-0100", "jane@globex.example.com"),
                    new AddressInfo("1 Main St", "Suite 200", "Springfield", "IL", "62701", "US")));
        }
    }

    @Test
    void getServiceProviderRefreshFlushesResponseCache() {
        final BroadWorksServer server = mock(BroadWorksServer.class);
        when(server.clearCache()).thenReturn(true);
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(server);

        final ServiceProvider sp = mock(ServiceProvider.class);
        when(sp.getServiceProviderId()).thenReturn("sp-9");
        when(sp.getServiceProviderName()).thenReturn("Globex");
        when(sp.getDefaultDomain()).thenReturn("globex.example.com");
        when(sp.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(sp.getResellerId()).thenReturn(null);
        when(sp.getSupportEmail()).thenReturn(null);
        when(sp.getContact()).thenReturn(null);
        when(sp.getAddress()).thenReturn(null);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(eq(server), eq("sp-9")))
                    .thenReturn(sp);

            tools.getServiceProvider("sp-9", null, true, null);

            verify(server).clearCache();
        }
    }

    @Test
    void getServiceProviderDoesNotFlushCacheWhenRefreshOmitted() {
        final BroadWorksServer server = mock(BroadWorksServer.class);
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(server);

        final ServiceProvider sp = mock(ServiceProvider.class);
        when(sp.getServiceProviderId()).thenReturn("sp-9");
        when(sp.getServiceProviderName()).thenReturn("Globex");
        when(sp.getDefaultDomain()).thenReturn("globex.example.com");
        when(sp.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(sp.getResellerId()).thenReturn(null);
        when(sp.getSupportEmail()).thenReturn(null);
        when(sp.getContact()).thenReturn(null);
        when(sp.getAddress()).thenReturn(null);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(eq(server), eq("sp-9")))
                    .thenReturn(sp);

            tools.getServiceProvider("sp-9", null);

            verify(server, never()).clearCache();
        }
    }

    @Test
    void getServiceProviderMapsNullContactAndAddressToNull() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        when(sp.getServiceProviderId()).thenReturn("sp-9");
        when(sp.getServiceProviderName()).thenReturn("Globex");
        when(sp.getDefaultDomain()).thenReturn("globex.example.com");
        when(sp.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(sp.getResellerId()).thenReturn(null);
        when(sp.getSupportEmail()).thenReturn(null);
        when(sp.getContact()).thenReturn(null);
        when(sp.getAddress()).thenReturn(null);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9"))).thenReturn(sp);

            final ServiceProviderDetail detail = tools.getServiceProvider("sp-9", null);

            assertThat(detail).isEqualTo(new ServiceProviderDetail(
                    "sp-9", "Globex", "globex.example.com", false, null, null, null, null));
            assertThat(detail.contact()).isNull();
            assertThat(detail.address()).isNull();
        }
    }

    @Test
    void modifyServiceProviderAppliesSuppliedFieldsAndReturnsRefreshedDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);

        final ServiceProvider refreshed = mock(ServiceProvider.class);
        when(refreshed.getServiceProviderId()).thenReturn("sp-9");
        when(refreshed.getServiceProviderName()).thenReturn("Globex New");
        when(refreshed.getDefaultDomain()).thenReturn("globex.example.com");
        when(refreshed.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(refreshed.getResellerId()).thenReturn(null);
        when(refreshed.getSupportEmail()).thenReturn("new@globex.example.com");
        when(refreshed.getContact()).thenReturn(null);
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderModifyRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9")))
                    .thenReturn(sp, refreshed);

            final ServiceProviderDetail detail = tools.modifyServiceProvider(
                    "sp-9", "Globex New", null, "new@globex.example.com",
                    null, null, "jane@globex.example.com",
                    null, null, "Metropolis", null, null, null, null);

            final ServiceProvider.ServiceProviderModifyRequest req = mocked.constructed().get(0);
            org.mockito.Mockito.verify(req).setServiceProviderName("Globex New");
            org.mockito.Mockito.verify(req).setSupportEmail("new@globex.example.com");
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).setDefaultDomain(any());

            // Only supplied sub-fields end up on the fresh Contact/StreetAddress; untouched ones stay unset
            // (raw null Optional) so they are omitted from the OCI request rather than re-sent.
            final ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(contactCaptor.capture());
            assertThat(contactCaptor.getValue().getContactEmail()).contains("jane@globex.example.com");
            assertThat(contactCaptor.getValue().getContactName()).isNull();

            final ArgumentCaptor<StreetAddress> addressCaptor = ArgumentCaptor.forClass(StreetAddress.class);
            org.mockito.Mockito.verify(req).setAddress(addressCaptor.capture());
            assertThat(addressCaptor.getValue().getCity()).contains("Metropolis");
            assertThat(addressCaptor.getValue().getCountry()).isNull();

            assertThat(detail).isEqualTo(new ServiceProviderDetail(
                    "sp-9", "Globex New", "globex.example.com", false, null,
                    "new@globex.example.com", null, null));
        }
    }

    @Test
    void modifyServiceProviderClearsFieldsWithEmptyStringButNeverClearsName() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);

        final ServiceProvider refreshed = mock(ServiceProvider.class);
        when(refreshed.getServiceProviderId()).thenReturn("sp-9");
        when(refreshed.getServiceProviderName()).thenReturn("Globex");
        when(refreshed.getDefaultDomain()).thenReturn("globex.example.com");
        when(refreshed.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(refreshed.getResellerId()).thenReturn(null);
        when(refreshed.getSupportEmail()).thenReturn(null);
        when(refreshed.getContact()).thenReturn(null);
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderModifyRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9")))
                    .thenReturn(sp, refreshed);

            tools.modifyServiceProvider(
                    "sp-9", "", null, "",
                    "", null, null,
                    null, null, null, null, null, null, null);

            final ServiceProvider.ServiceProviderModifyRequest req = mocked.constructed().get(0);
            // Name is never cleared: a blank display name is ignored (never passed to the setter).
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).setServiceProviderName(any());
            // A blank clearable field clears via setX(null) -> Optional.empty() (nil), NOT unsetX().
            org.mockito.Mockito.verify(req).setSupportEmail(null);
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).unsetSupportEmail();

            final ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(contactCaptor.capture());
            // Cleared field is present-but-empty (Optional.empty()), which serializes as a nil element.
            assertThat(contactCaptor.getValue().getContactName()).isNotNull().isEmpty();
        }
    }

    @Test
    void modifyServiceProviderCreatesNewContactWhenNoneExists() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);

        final ServiceProvider refreshed = mock(ServiceProvider.class);
        when(refreshed.getServiceProviderId()).thenReturn("sp-9");
        when(refreshed.getServiceProviderName()).thenReturn("Globex");
        when(refreshed.getDefaultDomain()).thenReturn("globex.example.com");
        when(refreshed.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(refreshed.getResellerId()).thenReturn(null);
        when(refreshed.getSupportEmail()).thenReturn(null);
        when(refreshed.getContact()).thenReturn(null);
        when(refreshed.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderModifyRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9")))
                    .thenReturn(sp, refreshed);

            tools.modifyServiceProvider(
                    "sp-9", null, null, null,
                    "Jane Doe", null, null,
                    null, null, null, null, null, null, null);

            final ServiceProvider.ServiceProviderModifyRequest req = mocked.constructed().get(0);
            final ArgumentCaptor<Contact> captor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(captor.capture());
            assertThat(captor.getValue().getContactName()).contains("Jane Doe");
        }
    }

    @Test
    void modifyServiceProviderThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4010");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderModifyRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderModifyRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9")))
                    .thenReturn(sp);

            assertThatThrownBy(() -> tools.modifyServiceProvider(
                    "sp-9", "Globex New", null, null,
                    null, null, null,
                    null, null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("modify service provider");
        }
    }

    @Test
    void failsWhenNoAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> tools.listServiceProviders(null, null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No authenticated user");
    }

    @Test
    void listServiceProvidersAppliesNameSearchCriteria() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider.ServiceProviderGetListResponse response =
                mock(ServiceProvider.ServiceProviderGetListResponse.class);
        when(response.isErrorResponse()).thenReturn(false);
        when(response.getServiceProviderTable()).thenReturn(List.of());

        try (MockedConstruction<ServiceProvider.ServiceProviderGetListRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderGetListRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            tools.listServiceProviders(null, null, "acme", "STARTSWITH", null);

            org.mockito.Mockito.verify(mocked.constructed().get(0))
                    .setSearchCriteriaServiceProviderName(any());
        }
    }

    @Test
    void listServiceProvidersRejectsInvalidSearchMode() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        try (MockedConstruction<ServiceProvider.ServiceProviderGetListRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderGetListRequest.class)) {

            assertThatThrownBy(() -> tools.listServiceProviders(null, null, "acme", "BOGUS", null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("Invalid searchMode");
        }
    }

    @Test
    void toPageBuildsColumnarPagesAndPagingMetadata() {
        final List<ServiceProviderSummary> all = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            all.add(new ServiceProviderSummary("sp-" + i, "Name " + i, i % 2 == 0, null));
        }

        final Page first = ServiceProviderTools.toPage(all, 0, 2);
        assertThat(first.schema()).isEqualTo(ServiceProviderTools.SERVICE_PROVIDER_SCHEMA);
        assertThat(first.rows()).containsExactly(
                Arrays.asList("sp-0", "Name 0", true, null),
                Arrays.asList("sp-1", "Name 1", false, null));
        assertThat(first.returned()).isEqualTo(2);
        assertThat(first.totalMatching()).isEqualTo(3);
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotNull();
        assertThat(first.truncationReason()).isNotNull();
        assertThat(first.suggestion()).contains(first.nextCursor());

        final Page second =
                ServiceProviderTools.toPage(all, Paging.decodeCursor(first.nextCursor()), 2);
        assertThat(second.rows()).containsExactly(Arrays.asList("sp-2", "Name 2", true, null));
        assertThat(second.returned()).isEqualTo(1);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.nextCursor()).isNull();
        assertThat(second.truncationReason()).isNull();
    }

    @Test
    void toPageBeyondEndReturnsEmptyPage() {
        final List<ServiceProviderSummary> all =
                List.of(new ServiceProviderSummary("sp-0", "Name 0", false, null));

        final Page page = ServiceProviderTools.toPage(all, 99, 10);
        assertThat(page.rows()).isEmpty();
        assertThat(page.returned()).isZero();
        assertThat(page.totalMatching()).isEqualTo(1);
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void createServiceProviderSendsRequiredFieldsAndReturnsDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider created = mock(ServiceProvider.class);
        when(created.getServiceProviderId()).thenReturn("sp-new");
        when(created.getServiceProviderName()).thenReturn("Acme");
        when(created.getDefaultDomain()).thenReturn("acme.example.com");
        when(created.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(created.getResellerId()).thenReturn(null);
        when(created.getSupportEmail()).thenReturn(null);
        when(created.getContact()).thenReturn(null);
        when(created.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderConsolidatedAddRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-new")))
                    .thenReturn(created);

            final ServiceProviderDetail detail = tools.createServiceProvider(
                    "sp-new", "Acme", "acme.example.com", null, "help@acme.example.com",
                    "Jane", null, null, null, null, null, null, null, null, null);

            final ServiceProvider.ServiceProviderConsolidatedAddRequest req = mocked.constructed().get(0);
            // The (server, String) constructor sets defaultDomain, not the id — the id must be set
            // explicitly or BroadWorks rejects the add with error 4073.
            org.mockito.Mockito.verify(req).setServiceProviderId("sp-new");
            org.mockito.Mockito.verify(req).setServiceProviderName("Acme");
            org.mockito.Mockito.verify(req).setDefaultDomain("acme.example.com");
            org.mockito.Mockito.verify(req).setSupportEmail("help@acme.example.com");
            org.mockito.Mockito.verify(req, org.mockito.Mockito.never()).setFlagIsEnterprise();

            final ArgumentCaptor<Contact> contactCaptor = ArgumentCaptor.forClass(Contact.class);
            org.mockito.Mockito.verify(req).setContact(contactCaptor.capture());
            assertThat(contactCaptor.getValue().getContactName()).contains("Jane");

            assertThat(detail).isEqualTo(new ServiceProviderDetail(
                    "sp-new", "Acme", "acme.example.com", false, null, null, null, null));
        }
    }

    @Test
    void createServiceProviderSetsEnterpriseFlagWhenRequested() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider created = mock(ServiceProvider.class);
        when(created.getServiceProviderId()).thenReturn("ent-1");
        when(created.getServiceProviderName()).thenReturn("Ent");
        when(created.getDefaultDomain()).thenReturn("ent.example.com");
        when(created.getIsEnterprise()).thenReturn(Boolean.TRUE);
        when(created.getResellerId()).thenReturn(null);
        when(created.getSupportEmail()).thenReturn(null);
        when(created.getContact()).thenReturn(null);
        when(created.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderConsolidatedAddRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("ent-1")))
                    .thenReturn(created);

            final ServiceProviderDetail detail = tools.createServiceProvider(
                    "ent-1", "Ent", "ent.example.com", true, null,
                    null, null, null, null, null, null, null, null, null, null);

            org.mockito.Mockito.verify(mocked.constructed().get(0)).setServiceProviderId("ent-1");
            org.mockito.Mockito.verify(mocked.constructed().get(0)).setFlagIsEnterprise();
            assertThat(detail.enterprise()).isTrue();
        }
    }

    @Test
    void createServiceProviderRejectsMissingRequiredField() {
        assertThatThrownBy(() -> tools.createServiceProvider(
                "  ", "Acme", "acme.example.com", null, null,
                null, null, null, null, null, null, null, null, null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("serviceProviderId is required");
    }

    @Test
    void createServiceProviderThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4003");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderConsolidatedAddRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {

            assertThatThrownBy(() -> tools.createServiceProvider(
                    "sp-dup", "Acme", "acme.example.com", null, null,
                    null, null, null, null, null, null, null, null, null, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("create service provider");
        }
    }

    @Test
    void getServiceProviderDoesNotElicitWhenIdPresent() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        final ServiceProvider sp = mock(ServiceProvider.class);
        when(sp.getServiceProviderId()).thenReturn("sp-9");
        when(sp.getServiceProviderName()).thenReturn("Globex");
        when(sp.getDefaultDomain()).thenReturn("globex.example.com");
        when(sp.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(sp.getResellerId()).thenReturn(null);
        when(sp.getSupportEmail()).thenReturn(null);
        when(sp.getContact()).thenReturn(null);
        when(sp.getAddress()).thenReturn(null);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9"))).thenReturn(sp);

            tools.getServiceProvider("sp-9", null, requestContext);

            verify(requestContext, never()).elicitEnabled();
            verify(requestContext, never()).elicit(any(), eq(ServiceProviderId.class));
        }
    }

    @Test
    void getServiceProviderUsesElicitedIdWhenMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ServiceProviderId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ServiceProviderId("sp-9"), Map.of()));

        final ServiceProvider sp = mock(ServiceProvider.class);
        when(sp.getServiceProviderId()).thenReturn("sp-9");
        when(sp.getServiceProviderName()).thenReturn("Globex");
        when(sp.getDefaultDomain()).thenReturn("globex.example.com");
        when(sp.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(sp.getResellerId()).thenReturn(null);
        when(sp.getSupportEmail()).thenReturn(null);
        when(sp.getContact()).thenReturn(null);
        when(sp.getAddress()).thenReturn(null);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9"))).thenReturn(sp);

            final ServiceProviderDetail detail = tools.getServiceProvider(null, null, requestContext);

            assertThat(detail.serviceProviderId()).isEqualTo("sp-9");
        }
    }

    @Test
    void createServiceProviderUsesElicitedRequiredFieldsWhenMissing() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(CreateServiceProviderDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new CreateServiceProviderDetails("sp-new", "Acme", "acme.example.com"), Map.of()));

        final ServiceProvider created = mock(ServiceProvider.class);
        when(created.getServiceProviderId()).thenReturn("sp-new");
        when(created.getServiceProviderName()).thenReturn("Acme");
        when(created.getDefaultDomain()).thenReturn("acme.example.com");
        when(created.getIsEnterprise()).thenReturn(Boolean.FALSE);
        when(created.getResellerId()).thenReturn(null);
        when(created.getSupportEmail()).thenReturn(null);
        when(created.getContact()).thenReturn(null);
        when(created.getAddress()).thenReturn(null);

        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderConsolidatedAddRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderConsolidatedAddRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-new")))
                    .thenReturn(created);

            final ServiceProviderDetail detail = tools.createServiceProvider(
                    null, null, null, null, null,
                    null, null, null, null, null, null, null, null, null, null, requestContext);

            org.mockito.Mockito.verify(mocked.constructed().get(0)).setServiceProviderId("sp-new");
            org.mockito.Mockito.verify(mocked.constructed().get(0)).setServiceProviderName("Acme");
            assertThat(detail.serviceProviderId()).isEqualTo("sp-new");
        }
    }

    @Test
    void createServiceProviderFailsWhenElicitationDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(CreateServiceProviderDetails.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() -> tools.createServiceProvider(
                null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("serviceProviderId, serviceProviderName and defaultDomain are required");
    }

    @Test
    void deleteServiceProviderReturnsConfirmation() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(false);

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderDeleteRequest> mocked =
                     mockConstruction(ServiceProvider.ServiceProviderDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            final String result = tools.deleteServiceProvider("sp-1", true, null);

            assertThat(mocked.constructed()).hasSize(1);
            assertThat(result).contains("sp-1");
        }
    }

    @Test
    void deleteServiceProviderRequiresAreYouSure() {
        assertThatThrownBy(() -> tools.deleteServiceProvider("sp-1", null, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure")
                .hasMessageContaining("areYouSure=true");
        assertThatThrownBy(() -> tools.deleteServiceProvider("sp-1", false, null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure");
        verify(connectionFactory, never()).connect(any(), any());
    }

    @Test
    void deleteServiceProviderThrowsOnErrorResponse() {
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(null);

        final ServiceProvider sp = mock(ServiceProvider.class);
        final DefaultResponse response = mock(DefaultResponse.class);
        when(response.isErrorResponse()).thenReturn(true);
        when(response.getErrorCode()).thenReturn("4008");

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class);
             MockedConstruction<ServiceProvider.ServiceProviderDeleteRequest> ignored =
                     mockConstruction(ServiceProvider.ServiceProviderDeleteRequest.class,
                             (m, ctx) -> when(m.fire()).thenReturn(response))) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-1"))).thenReturn(sp);

            assertThatThrownBy(() -> tools.deleteServiceProvider("sp-1", true, null))
                    .isInstanceOf(AlpacaException.class)
                    .hasMessageContaining("delete service provider");
        }
    }
}
