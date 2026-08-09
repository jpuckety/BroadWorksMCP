package com.broadworks.mcp.mcp.tools;

import java.util.List;

import com.broadworks.mcp.auth.session.UserContext;
import com.broadworks.mcp.auth.session.UserInfo;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.messaging.response.Response;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks service providers, backed by the Alpaca toolkit.
 *
 * <p>Every operation runs against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject} via the {@link AlpacaConnectionFactory}); results are mapped to compact DTOs. No
 * credentials or protocol bodies are logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ServiceProviderTools {

    private final AlpacaConnectionFactory connectionFactory;

    @Tool(name = "broadworks_list_service_providers",
            description = "List the BroadWorks service providers (and enterprises) accessible to the "
                    + "authenticated user.")
    public List<ServiceProviderSummary> listServiceProviders(
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider.ServiceProviderGetListResponse response =
                    new ServiceProvider.ServiceProviderGetListRequest(server).fire();
            ensureSuccess(response, "list service providers");
            return response.getServiceProviderTable().stream()
                    .map(row -> new ServiceProviderSummary(
                            row.getServiceProviderId(),
                            row.getServiceProviderName(),
                            Boolean.parseBoolean(row.getIsEnterprise()),
                            row.getResellerId()))
                    .toList();
        } catch (AlpacaException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AlpacaException("Failed to list service providers", ex);
        }
    }

    @Tool(name = "broadworks_get_service_provider",
            description = "Get details for a single BroadWorks service provider by id.")
    public ServiceProviderDetail getServiceProvider(
            @ToolParam(description = "The service provider id") String serviceProviderId,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider sp = ServiceProvider.getPopulatedServiceProvider(server, serviceProviderId);
            return new ServiceProviderDetail(
                    sp.getServiceProviderId(),
                    sp.getServiceProviderName(),
                    sp.getDefaultDomain(),
                    Boolean.TRUE.equals(sp.getIsEnterprise()),
                    sp.getResellerId());
        } catch (BroadWorksObjectException ex) {
            throw new AlpacaException("Service provider not found or not accessible: " + serviceProviderId, ex);
        }
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }

    static void ensureSuccess(Response response, String action) {
        if (response.isErrorResponse()) {
            throw new AlpacaException("BroadWorks failed to " + action
                    + " (error code " + response.getErrorCode() + ")");
        }
    }
}
