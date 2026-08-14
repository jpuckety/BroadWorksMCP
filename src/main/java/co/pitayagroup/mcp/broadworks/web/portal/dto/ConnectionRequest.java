package co.pitayagroup.mcp.broadworks.web.portal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Create/update payload for a BroadWorks connection submitted from the web portal.
 *
 * <p>The {@code password} is optional: on create a blank value leaves the connection needing a
 * password (set later via the dedicated password endpoint); on update a blank/absent value leaves the
 * stored secret unchanged. Passwords are never echoed back to the browser.</p>
 */
public record ConnectionRequest(
        @NotBlank(message = "displayName is required") String displayName,
        @NotBlank(message = "hostname is required") String hostname,
        @Min(value = 1, message = "port must be between 1 and 65535")
        @Max(value = 65535, message = "port must be between 1 and 65535") int port,
        @NotBlank(message = "username is required") String username,
        String loginType,
        Boolean usePrivateApplicationServerAddress,
        String password) {
}
