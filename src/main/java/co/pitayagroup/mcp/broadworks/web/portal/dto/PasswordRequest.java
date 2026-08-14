package co.pitayagroup.mcp.broadworks.web.portal.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Payload for the dedicated set/update-password endpoint. A blank password is rejected so the portal
 * cannot accidentally clear a connection's secret; the password is never echoed back.
 */
public record PasswordRequest(
        @NotBlank(message = "password is required") String password) {
}
