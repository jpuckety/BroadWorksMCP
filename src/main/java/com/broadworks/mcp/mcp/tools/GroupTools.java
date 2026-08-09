package com.broadworks.mcp.mcp.tools;

import java.util.List;

import com.broadworks.mcp.auth.session.UserContext;
import com.broadworks.mcp.auth.session.UserInfo;
import com.broadworks.mcp.mcp.AlpacaConnectionFactory;
import com.broadworks.mcp.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.exception.BroadWorksObjectException;
import co.ecg.alpaca.toolkit.generated.Group;
import co.ecg.alpaca.toolkit.generated.ServiceProvider;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for BroadWorks groups within a service provider, backed by the Alpaca toolkit.
 *
 * <p>Operations run against the authenticated user's own BroadWorks connection (resolved by
 * {@code subject}); results are mapped to compact DTOs. No credentials or protocol bodies are
 * logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GroupTools {

    private final AlpacaConnectionFactory connectionFactory;

    @Tool(name = "broadworks_list_groups",
            description = "List the groups within a BroadWorks service provider.")
    public List<GroupSummary> listGroups(
            @ToolParam(description = "The service provider id whose groups to list") String serviceProviderId,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
            final Group.GroupGetListInServiceProviderResponse response =
                    new Group.GroupGetListInServiceProviderRequest(serviceProvider).fire();
            ServiceProviderTools.ensureSuccess(response, "list groups");
            return response.getGroupTable().stream()
                    .map(row -> new GroupSummary(row.getGroupId(), row.getGroupName(), row.getUserLimit()))
                    .toList();
        } catch (AlpacaException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new AlpacaException("Failed to list groups in service provider " + serviceProviderId, ex);
        }
    }

    @Tool(name = "broadworks_get_group",
            description = "Get details for a single BroadWorks group within a service provider.")
    public GroupDetail getGroup(
            @ToolParam(description = "The service provider id") String serviceProviderId,
            @ToolParam(description = "The group id") String groupId,
            @ToolParam(required = false,
                    description = "Optional BroadWorks resource id when multiple connections are configured")
            String resourceId) {
        final BroadWorksServer server = connect(resourceId);
        try {
            final ServiceProvider serviceProvider = new ServiceProvider(server, serviceProviderId);
            final Group group = Group.getPopulatedGroup(serviceProvider, groupId);
            return new GroupDetail(
                    group.getGroupId(),
                    group.getGroupName(),
                    group.getServiceProviderId(),
                    group.getDefaultDomain());
        } catch (BroadWorksObjectException ex) {
            throw new AlpacaException("Group not found or not accessible: " + serviceProviderId + "/" + groupId, ex);
        }
    }

    private BroadWorksServer connect(String resourceId) {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return connectionFactory.connect(user.subject(), resourceId);
    }
}
