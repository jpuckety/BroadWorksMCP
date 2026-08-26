package co.pitayagroup.mcp.broadworks.mcp.approval;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.config.PublicBaseUrlProperties;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import io.modelcontextprotocol.spec.McpSchema.ClientCapabilities;
import io.modelcontextprotocol.spec.McpSchema.ElicitUrlRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.stereotype.Service;

/**
 * Destructive-action confirmation: URL-mode portal approval when the client supports it, otherwise
 * an explicit {@code areYouSure=true} flag. Form-mode elicitation is never used for approvals.
 *
 * <p>When URL elicitation is available the {@code areYouSure} flag is ignored. After the client
 * unblocks, the delete proceeds only if this JVM's store is {@link ApprovalDecision#APPROVED} for
 * the same Google subject — a client {@code ACCEPT} alone is not consent.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConfirmationService {

    static final Duration APPROVAL_TTL = Duration.ofMinutes(15);

    private final PendingApprovalStore pendingApprovalStore;
    private final PublicBaseUrlProperties publicBaseUrl;

    /**
     * Gate a destructive tool. URL-capable clients always wait on the portal. Others must pass
     * {@code areYouSure=true} or the call is refused with no BroadWorks change.
     */
    public void requireAreYouSure(Boolean areYouSure, String action, McpSyncRequestContext ctx) {
        if (supportsUrlElicitation(ctx)) {
            confirmViaPortal(action, ctx);
            return;
        }
        if (Boolean.TRUE.equals(areYouSure)) {
            return;
        }
        throw new AlpacaException(fallbackRefusalMessage(action));
    }

    private void confirmViaPortal(String action, McpSyncRequestContext ctx) {
        final String subject = UserContext.current()
                .map(UserInfo::subject)
                .filter(value -> value != null && !value.isBlank())
                .orElseThrow(() -> new AlpacaException(
                        "Cannot confirm " + action + " without an authenticated user. No changes were made."));
        final String sessionId = ctx.sessionId();
        if (sessionId == null || sessionId.isBlank()) {
            throw new AlpacaException(
                    "Cannot confirm " + action + " without an MCP session. No changes were made.");
        }

        final String elicitationId = UUID.randomUUID().toString();
        pendingApprovalStore.create(new PendingApproval(
                elicitationId, subject, sessionId, action, ApprovalDecision.PENDING,
                Instant.now().plus(APPROVAL_TTL)));

        final String url = publicBaseUrl.baseUrl() + "/portal/approvals/" + elicitationId;
        final String message = "Confirm you want to " + action
                + ". Open the approval page in the portal, then you can close that tab.";
        log.info("Requesting portal confirmation for {} (elicitationId={})", action, elicitationId);
        ctx.elicit(ElicitUrlRequest.builder(message, url, elicitationId).build());

        final boolean approved = pendingApprovalStore.get(elicitationId)
                .filter(approval -> subject.equals(approval.subject()))
                .filter(approval -> approval.decision() == ApprovalDecision.APPROVED)
                .isPresent();
        if (approved) {
            pendingApprovalStore.remove(elicitationId);
            return;
        }
        throw new AlpacaException("Confirmation for " + action
                + " was not approved in the portal. No changes were made.");
    }

    private static boolean supportsUrlElicitation(McpSyncRequestContext ctx) {
        if (ctx == null) {
            return false;
        }
        final ClientCapabilities capabilities = ctx.clientCapabilities();
        return capabilities != null
                && capabilities.elicitation() != null
                && capabilities.elicitation().url() != null;
    }

    private static String fallbackRefusalMessage(String action) {
        return "Are you sure you want to " + action
                + "? Use a URL-capable MCP client to confirm in the portal, or set areYouSure=true. "
                + "No changes were made.";
    }
}
