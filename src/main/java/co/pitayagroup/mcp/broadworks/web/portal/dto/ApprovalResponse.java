package co.pitayagroup.mcp.broadworks.web.portal.dto;

/**
 * Subject-scoped view of a pending destructive-action approval shown on the portal page.
 */
public record ApprovalResponse(String id, String action, String status) {
}
