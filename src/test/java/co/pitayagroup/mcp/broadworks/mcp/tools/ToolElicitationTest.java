package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.GroupRef;
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.ServicePackRef;
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.ServiceProviderId;
import co.pitayagroup.mcp.broadworks.mcp.tools.ToolElicitation.UserId;

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;

@ExtendWith(MockitoExtension.class)
class ToolElicitationTest {

    @Mock
    private McpSyncRequestContext requestContext;

    @Test
    void canElicitRequiresEnabledContext() {
        assertThat(ToolElicitation.canElicit(null)).isFalse();
        when(requestContext.elicitEnabled()).thenReturn(false);
        assertThat(ToolElicitation.canElicit(requestContext)).isFalse();
        when(requestContext.elicitEnabled()).thenReturn(true);
        assertThat(ToolElicitation.canElicit(requestContext)).isTrue();
    }

    @Test
    void resolveServiceProviderIdSkipsElicitWhenAlreadyPresent() {
        assertThat(ToolElicitation.resolveServiceProviderId("sp-1", requestContext)).isEqualTo("sp-1");
        verify(requestContext, never()).elicitEnabled();
        verify(requestContext, never()).elicit(any(), eq(ServiceProviderId.class));
    }

    @Test
    void resolveServiceProviderIdReturnsElicitedValue() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ServiceProviderId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ServiceProviderId("sp-elicited"), Map.of()));

        assertThat(ToolElicitation.resolveServiceProviderId(null, requestContext)).isEqualTo("sp-elicited");
    }

    @Test
    void resolveServiceProviderIdThrowsWhenDeclined() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(ServiceProviderId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.DECLINE, null, Map.of()));

        assertThatThrownBy(() -> ToolElicitation.resolveServiceProviderId("  ", requestContext))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("serviceProviderId is required");
    }

    @Test
    void resolveGroupRefMergesProvidedAndElicitedFields() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(GroupRef.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new GroupRef("elicited-sp", "elicited-grp"), Map.of()));

        final GroupRef merged = ToolElicitation.resolveGroupRef("kept-sp", null, requestContext);

        assertThat(merged.serviceProviderId()).isEqualTo("kept-sp");
        assertThat(merged.groupId()).isEqualTo("elicited-grp");
    }

    @Test
    void resolveUserIdAndServicePackRefElicitWhenMissing() {
        when(requestContext.elicitEnabled()).thenReturn(true);
        when(requestContext.elicit(any(), eq(UserId.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new UserId("user-1"), Map.of()));
        when(requestContext.elicit(any(), eq(ServicePackRef.class)))
                .thenReturn(new StructuredElicitResult<>(ElicitResult.Action.ACCEPT,
                        new ServicePackRef("sp-1", "Gold"), Map.of()));

        assertThat(ToolElicitation.resolveUserId(null, requestContext)).isEqualTo("user-1");
        final ServicePackRef pack = ToolElicitation.resolveServicePackRef(null, null, requestContext);
        assertThat(pack.serviceProviderId()).isEqualTo("sp-1");
        assertThat(pack.servicePackName()).isEqualTo("Gold");
    }

    @Test
    void firstNonBlankAndFirstNonNullPreferOriginal() {
        assertThat(ToolElicitation.firstNonBlank("kept", "elicited")).isEqualTo("kept");
        assertThat(ToolElicitation.firstNonBlank("  ", "elicited")).isEqualTo("elicited");
        assertThat(ToolElicitation.firstNonNull(5, 9)).isEqualTo(5);
        assertThat(ToolElicitation.firstNonNull(null, 9)).isEqualTo(9);
    }

    @Test
    void requireAreYouSureAcceptsOnlyTrue() {
        ToolElicitation.requireAreYouSure(true, "delete service provider 'sp-1'");

        assertThatThrownBy(() -> ToolElicitation.requireAreYouSure(null, "delete service provider 'sp-1'"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("Are you sure")
                .hasMessageContaining("areYouSure=true")
                .hasMessageContaining("No changes were made");
        assertThatThrownBy(() -> ToolElicitation.requireAreYouSure(false, "delete user 'u-1'"))
                .isInstanceOf(AlpacaException.class)
                .hasMessageContaining("delete user 'u-1'");
    }
}
