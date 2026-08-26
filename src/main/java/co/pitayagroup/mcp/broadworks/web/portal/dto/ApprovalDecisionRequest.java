package co.pitayagroup.mcp.broadworks.web.portal.dto;

import co.pitayagroup.mcp.broadworks.mcp.approval.ApprovalDecision;

import jakarta.validation.constraints.NotNull;

/**
 * Confirm or deny payload for a pending portal approval. Only {@link ApprovalDecision#APPROVED}
 * and {@link ApprovalDecision#DECLINED} are accepted.
 */
public record ApprovalDecisionRequest(
        @NotNull(message = "decision is required") ApprovalDecision decision) {
}
