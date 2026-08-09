package com.broadworks.mcp.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import com.broadworks.mcp.auth.session.UserInfo;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;

import co.ecg.alpaca.toolkit.generated.Group;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
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

            final List<GroupSummary> result = tools.listGroups("sp-1", null);

            assertThat(result).containsExactly(new GroupSummary("grp-1", "Sales", "25"));
            assertThat(spCtor.constructed()).hasSize(1);
            assertThat(reqCtor.constructed()).hasSize(1);
        }
    }

    @Test
    void getGroupMapsToDetail() {
        when(connectionFactory.connect(eq("sub-1"), eq("res-2"))).thenReturn(null);

        final Group group = mock(Group.class);
        when(group.getGroupId()).thenReturn("grp-9");
        when(group.getGroupName()).thenReturn("Support");
        when(group.getServiceProviderId()).thenReturn("sp-1");
        when(group.getDefaultDomain()).thenReturn("sp1.example.com");

        try (MockedConstruction<ServiceProvider> spCtor = mockConstruction(ServiceProvider.class);
             MockedStatic<Group> groupStatics = mockStatic(Group.class)) {
            groupStatics.when(() -> Group.getPopulatedGroup(org.mockito.ArgumentMatchers.any(), eq("grp-9")))
                    .thenReturn(group);

            final GroupDetail detail = tools.getGroup("sp-1", "grp-9", "res-2");

            assertThat(detail).isEqualTo(new GroupDetail("grp-9", "Support", "sp-1", "sp1.example.com"));
            assertThat(spCtor.constructed()).hasSize(1);
        }
    }
}
