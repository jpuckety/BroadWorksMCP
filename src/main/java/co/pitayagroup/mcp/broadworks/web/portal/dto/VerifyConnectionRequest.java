package co.pitayagroup.mcp.broadworks.web.portal.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * Payload for testing a BroadWorks connection from the web portal before (or without) saving it.
 *
 * <p>Carries the non-secret target fields the user is editing plus an optional {@code password}. When
 * the {@code password} is blank an existing connection's stored secret is used instead — identified by
 * {@code resourceId} — so the portal can verify a saved connection, or re-test one whose host / port /
 * username has been changed, without ever echoing the stored password back to the browser. The
 * password is only ever sent, never returned.</p>
 */
public record VerifyConnectionRequest(
        @NotBlank(message = "hostname is required") String hostname,
        @Min(value = 1, message = "port must be between 1 and 65535")
        @Max(value = 65535, message = "port must be between 1 and 65535") int port,
        @NotBlank(message = "username is required") String username,
        String password,
        String resourceId) {
}
