package co.pitayagroup.mcp.broadworks.mcp.approval;

import java.time.Instant;

/**
 * In-memory record of a portal confirmation that unblocks a delete tool.
 *
 * @param elicitationId MCP elicitation id and portal path segment
 * @param subject       Google subject that owns the MCP session
 * @param sessionId     MCP session waiting on {@code elicit()}
 * @param action        human-readable description of the destructive action
 * @param decision      current decision; only {@link ApprovalDecision#APPROVED} authorizes the delete
 * @param expiresAt     instant after which the row is treated as missing
 */
public record PendingApproval(
        String elicitationId,
        String subject,
        String sessionId,
        String action,
        ApprovalDecision decision,
        Instant expiresAt) {

    public PendingApproval withDecision(ApprovalDecision newDecision) {
        return new PendingApproval(elicitationId, subject, sessionId, action, newDecision, expiresAt);
    }
}
