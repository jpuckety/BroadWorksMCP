package com.broadworks.mcp.mcp.tools;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.broadworks.mcp.auth.session.UserContext;
import com.broadworks.mcp.auth.session.UserInfo;
import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.mcp.AlpacaException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

/**
 * MCP tools for managing the authenticated user's own BroadWorks/Alpaca connections.
 *
 * <p>These tools let an MCP client add, list, and remove the per-tenant connection resources that the
 * {@code AlpacaConnectionFactory} later resolves when a BroadWorks operation runs. Every operation is
 * scoped to the caller's {@code subject}; secrets are encrypted at rest by the {@link ResourceStore}
 * and are never returned or logged.</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConnectionTools {

    private static final String DEFAULT_LOGIN_TYPE = "SYSTEM";

    private final ResourceStore resourceStore;

    @Tool(name = "broadworks_list_connections",
            description = "List the BroadWorks server connections configured for the authenticated user "
                    + "(passwords are never returned).")
    public List<ConnectionSummary> listConnections() {
        final String subject = currentSubject();
        return resourceStore.listForUser(subject).stream()
                .map(ConnectionSummary::from)
                .toList();
    }

    @Tool(name = "broadworks_add_connection",
            description = "Add (or replace) a BroadWorks server connection for the authenticated user. "
                    + "Returns a summary of the stored connection; the password is stored encrypted and "
                    + "never returned.")
    public ConnectionSummary addConnection(
            @ToolParam(description = "Human-friendly name / nickname for the connection, e.g. 'ECG Production'")
            String displayName,
            @ToolParam(description = "BroadWorks OCI hostname (no scheme or path), e.g. portal.example.com")
            String hostname,
            @ToolParam(description = "BroadWorks OCI port, e.g. 2208") int port,
            @ToolParam(description = "BroadWorks login username") String username,
            @ToolParam(description = "BroadWorks login password (stored encrypted at rest)") String password,
            @ToolParam(required = false,
                    description = "Login type: SYSTEM, PROVISIONING, or SERVICEPROVIDER (defaults to SYSTEM)")
            String loginType,
            @ToolParam(required = false,
                    description = "Whether to use the private application server address (defaults to false)")
            Boolean usePrivateApplicationServerAddress,
            @ToolParam(required = false,
                    description = "Explicit resource id to create/replace; when omitted a stable id is "
                            + "derived from the display name")
            String resourceId) {
        final String subject = currentSubject();

        if (hostname == null || hostname.isBlank()) {
            throw new AlpacaException("hostname is required");
        }
        if (port <= 0 || port > 65535) {
            throw new AlpacaException("port must be between 1 and 65535");
        }
        if (username == null || username.isBlank()) {
            throw new AlpacaException("username is required");
        }
        if (password == null || password.isBlank()) {
            throw new AlpacaException("password is required");
        }

        final String effectiveResourceId = resolveResourceId(resourceId, displayName);
        final String effectiveDisplayName = (displayName == null || displayName.isBlank())
                ? effectiveResourceId : displayName;
        final String effectiveLoginType = (loginType == null || loginType.isBlank())
                ? DEFAULT_LOGIN_TYPE : loginType.trim().toUpperCase(Locale.ROOT);
        final boolean privateAddress = Boolean.TRUE.equals(usePrivateApplicationServerAddress);

        final AlpacaResource resource = new AlpacaResource(
                effectiveResourceId,
                effectiveDisplayName,
                hostname.trim(),
                port,
                effectiveLoginType,
                username,
                password,
                privateAddress);
        resourceStore.put(subject, resource);
        log.info("Stored BroadWorks connection resourceId={} host={} loginType={} (secret encrypted at rest)",
                effectiveResourceId, resource.hostname(), effectiveLoginType);
        return ConnectionSummary.from(resource);
    }

    @Tool(name = "broadworks_delete_connection",
            description = "Delete a BroadWorks server connection owned by the authenticated user.")
    public String deleteConnection(
            @ToolParam(description = "The resource id of the connection to delete") String resourceId) {
        if (resourceId == null || resourceId.isBlank()) {
            throw new AlpacaException("resourceId is required");
        }
        final String subject = currentSubject();
        resourceStore.delete(subject, resourceId);
        log.info("Deleted BroadWorks connection resourceId={}", resourceId);
        return "Deleted BroadWorks connection '" + resourceId + "'";
    }

    private static String resolveResourceId(String resourceId, String displayName) {
        if (resourceId != null && !resourceId.isBlank()) {
            return resourceId.trim();
        }
        final String slug = slugify(displayName);
        return slug.isBlank() ? UUID.randomUUID().toString() : slug;
    }

    private static String slugify(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
    }

    private static String currentSubject() {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new AlpacaException("No authenticated user in context"));
        return user.subject();
    }
}
