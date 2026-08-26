package co.pitayagroup.mcp.broadworks.mcp.tools;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.context.StructuredElicitResult;

/**
 * Shared MCP elicitation helpers for BroadWorks tools.
 *
 * <p>Required identifier and create fields are advertised as optional on the tool schema so a
 * client that supports elicitation can be prompted instead of failing immediately. Already-supplied
 * values are kept. Optional filters, pagination, connection {@code resourceId}, passwords,
 * service lists, and the delete {@code areYouSure} flag are never elicited. Destructive
 * confirmation is handled by {@code ConfirmationService}.</p>
 */
@Slf4j
final class ToolElicitation {

    private ToolElicitation() {
    }

    static boolean canElicit(McpSyncRequestContext requestContext) {
        return requestContext != null && requestContext.elicitEnabled();
    }

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static String firstNonBlank(String original, String elicited) {
        return isBlank(original) ? elicited : original;
    }

    static Integer firstNonNull(Integer original, Integer elicited) {
        return original != null ? original : elicited;
    }

    static <T> T elicit(McpSyncRequestContext requestContext, String message, Class<T> type,
            String declinedMessage) {
        log.info("Initiating elicitation");
        final StructuredElicitResult<T> elicitResult = requestContext.elicit(e -> e.message(message), type);
        if (!ElicitResult.Action.ACCEPT.equals(elicitResult.action())
                || elicitResult.structuredContent() == null) {
            throw new AlpacaException(declinedMessage);
        }
        return elicitResult.structuredContent();
    }

    static String resolveServiceProviderId(String serviceProviderId, McpSyncRequestContext requestContext) {
        if (!isBlank(serviceProviderId) || !canElicit(requestContext)) {
            return serviceProviderId;
        }
        final ServiceProviderId elicited = elicit(requestContext, "Service provider id is required.",
                ServiceProviderId.class, "serviceProviderId is required");
        return elicited.serviceProviderId();
    }

    static GroupRef resolveGroupRef(String serviceProviderId, String groupId,
            McpSyncRequestContext requestContext) {
        if ((!isBlank(serviceProviderId) && !isBlank(groupId)) || !canElicit(requestContext)) {
            return new GroupRef(serviceProviderId, groupId);
        }
        final GroupRef elicited = elicit(requestContext,
                "Service provider id and group id are required.",
                GroupRef.class, "serviceProviderId and groupId are required");
        log.info("Elicitation accepted for group ref (serviceProviderId={}, groupId={})",
                firstNonBlank(serviceProviderId, elicited.serviceProviderId()),
                firstNonBlank(groupId, elicited.groupId()));
        return new GroupRef(
                firstNonBlank(serviceProviderId, elicited.serviceProviderId()),
                firstNonBlank(groupId, elicited.groupId()));
    }

    static String resolveUserId(String userId, McpSyncRequestContext requestContext) {
        if (!isBlank(userId) || !canElicit(requestContext)) {
            return userId;
        }
        final UserId elicited = elicit(requestContext, "User id is required.",
                UserId.class, "userId is required");
        return elicited.userId();
    }

    static ServicePackRef resolveServicePackRef(String serviceProviderId, String servicePackName,
            McpSyncRequestContext requestContext) {
        if ((!isBlank(serviceProviderId) && !isBlank(servicePackName)) || !canElicit(requestContext)) {
            return new ServicePackRef(serviceProviderId, servicePackName);
        }
        final ServicePackRef elicited = elicit(requestContext,
                "Service provider id and service pack name are required.",
                ServicePackRef.class, "serviceProviderId and servicePackName are required");
        log.info("Elicitation accepted for service pack (serviceProviderId={}, servicePackName={})",
                firstNonBlank(serviceProviderId, elicited.serviceProviderId()),
                firstNonBlank(servicePackName, elicited.servicePackName()));
        return new ServicePackRef(
                firstNonBlank(serviceProviderId, elicited.serviceProviderId()),
                firstNonBlank(servicePackName, elicited.servicePackName()));
    }

    record ServiceProviderId(String serviceProviderId) {
    }

    record GroupRef(String serviceProviderId, String groupId) {
    }

    record UserId(String userId) {
    }

    record ServicePackRef(String serviceProviderId, String servicePackName) {
    }
}
