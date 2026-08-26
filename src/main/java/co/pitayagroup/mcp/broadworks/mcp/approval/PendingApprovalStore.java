package co.pitayagroup.mcp.broadworks.mcp.approval;

import java.util.Optional;

/**
 * Stores pending portal approvals for MCP URL elicitation.
 *
 * <p>Expired rows are treated as missing. {@link #decide} is atomic: a wrong, expired, or unknown
 * id/subject yields empty; an already-decided row is returned as-is (same decision is idempotent;
 * a flipped decision is a conflict the caller detects from the returned row).</p>
 */
public interface PendingApprovalStore {

    void create(PendingApproval approval);

    Optional<PendingApproval> get(String elicitationId);

    Optional<PendingApproval> decide(String elicitationId, String subject, ApprovalDecision decision);

    void remove(String elicitationId);
}
