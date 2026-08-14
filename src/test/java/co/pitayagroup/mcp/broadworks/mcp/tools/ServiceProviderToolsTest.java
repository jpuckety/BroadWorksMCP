package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class ServiceProviderToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private ServiceProviderTools tools;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new ServiceProviderTools(connectionFactory);
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
}
