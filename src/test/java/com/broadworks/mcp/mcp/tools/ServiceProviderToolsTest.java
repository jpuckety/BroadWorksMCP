package com.broadworks.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.broadworks.mcp.auth.session.UserInfo;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.generated.ServiceProvider;
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

            final List<ServiceProviderSummary> result = tools.listServiceProviders("res-1");

            assertThat(result).containsExactly(new ServiceProviderSummary("sp-1", "Acme", true, "res-x"));
            assertThat(mocked.constructed()).hasSize(1);
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

            assertThatThrownBy(() -> tools.listServiceProviders(null))
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

        try (MockedStatic<ServiceProvider> statics = mockStatic(ServiceProvider.class)) {
            statics.when(() -> ServiceProvider.getPopulatedServiceProvider(any(), eq("sp-9"))).thenReturn(sp);

            final ServiceProviderDetail detail = tools.getServiceProvider("sp-9", null);

            assertThat(detail).isEqualTo(
                    new ServiceProviderDetail("sp-9", "Globex", "globex.example.com", false, null));
        }
    }

    @Test
    void failsWhenNoAuthenticatedUser() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(() -> tools.listServiceProviders(null))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("No authenticated user");
    }
}
