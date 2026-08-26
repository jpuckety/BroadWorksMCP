package co.pitayagroup.mcp.broadworks.web.portal;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.approval.ApprovalDecision;
import co.pitayagroup.mcp.broadworks.mcp.approval.PendingApproval;
import co.pitayagroup.mcp.broadworks.mcp.approval.PendingApprovalStore;
import co.pitayagroup.mcp.broadworks.web.portal.dto.ApprovalDecisionRequest;
import co.pitayagroup.mcp.broadworks.web.portal.dto.ApprovalResponse;

import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.spec.McpSchema.ElicitationCompleteNotification;
import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JSON API for the portal approval page. Confirm/Deny is scoped to the same Google subject as the
 * waiting MCP session; a successful write then unblocks that session via elicitation complete.
 */
@Slf4j
@RestController
@RequestMapping("/api/portal/approvals")
public class PortalApprovalController {

    private final PendingApprovalStore pendingApprovalStore;
    private final ObjectProvider<McpSyncServer> mcpSyncServer;

    public PortalApprovalController(PendingApprovalStore pendingApprovalStore,
                                    ObjectProvider<McpSyncServer> mcpSyncServer) {
        this.pendingApprovalStore = pendingApprovalStore;
        this.mcpSyncServer = mcpSyncServer;
    }

    @GetMapping("/{id}")
    public ApprovalResponse get(@PathVariable String id) {
        return toResponse(requireOwned(id));
    }

    @PostMapping("/{id}")
    public ApprovalResponse decide(@PathVariable String id,
                                   @Valid @RequestBody ApprovalDecisionRequest request) {
        final ApprovalDecision decision = request.decision();
        if (decision != ApprovalDecision.APPROVED && decision != ApprovalDecision.DECLINED) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "decision must be APPROVED or DECLINED");
        }

        final PendingApproval updated = pendingApprovalStore.decide(id, currentSubject(), decision)
                .orElseThrow(() -> notFound(id));
        if (updated.decision() != decision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Approval already decided");
        }
        notifyComplete(updated);
        return toResponse(updated);
    }

    private PendingApproval requireOwned(String id) {
        final String subject = currentSubject();
        return pendingApprovalStore.get(id)
                .filter(approval -> subject.equals(approval.subject()))
                .orElseThrow(() -> notFound(id));
    }

    private void notifyComplete(PendingApproval approval) {
        final McpSyncServer server = mcpSyncServer.getIfAvailable();
        if (server == null) {
            log.warn("MCP server is not available; cannot complete elicitation {}",
                    approval.elicitationId());
            return;
        }
        try {
            server.sendElicitationComplete(approval.sessionId(),
                    new ElicitationCompleteNotification(approval.elicitationId()));
        } catch (RuntimeException ex) {
            log.warn("Failed to send elicitation complete for elicitationId={} session={}: {}",
                    approval.elicitationId(), approval.sessionId(), ex.getMessage());
        }
    }

    private static ApprovalResponse toResponse(PendingApproval approval) {
        return new ApprovalResponse(approval.elicitationId(), approval.action(),
                approval.decision().name());
    }

    private static ResponseStatusException notFound(String id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "No such approval: " + id);
    }

    private static String currentSubject() {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No authenticated user in context"));
        return user.subject();
    }
}
