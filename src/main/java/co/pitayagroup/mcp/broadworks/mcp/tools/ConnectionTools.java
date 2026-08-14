package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;
import co.pitayagroup.mcp.broadworks.mcp.model.ConnectionSummary;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * MCP tools for managing the authenticated user's own BroadWorks/Alpaca connections.
 *
 * <p>These tools let an MCP client add, list, and remove the per-tenant connection resources that the
 * {@code AlpacaConnectionFactory} later resolves when a BroadWorks operation runs. Every operation is
 * scoped to the caller's {@code subject}; secrets are encrypted at rest by the {@link ResourceStore}
 * and are never returned or logged. Connection targets are screened by the {@link HostAllowlist} so a
 * caller cannot point the server at internal infrastructure (SSRF).</p>
 */
@Slf4j
@Component
public class ConnectionTools {

    private static final String DEFAULT_LOGIN_TYPE = "SYSTEM";

    private final ResourceStore resourceStore;
    private final HostAllowlist hostAllowlist;

    @Autowired
    public ConnectionTools(ResourceStore resourceStore, HostAllowlist hostAllowlist) {
        this.resourceStore = resourceStore;
        this.hostAllowlist = hostAllowlist;
    }

    /**
     * Convenience constructor for contexts that carry no SSRF configuration; applies the secure
     * default (private / loopback / link-local targets blocked).
     */
    public ConnectionTools(ResourceStore resourceStore) {
        this(resourceStore, new HostAllowlist(false));
    }

    @Tool(name = "broadworks_list_connections",
            description = "List the BroadWorks server connections configured for the authenticated user "
                    + "(passwords are never returned).")
    public List<ConnectionSummary> listConnections() {
        final String subject = currentSubject();
        final List<ConnectionSummary> summaries = resourceStore.listForUser(subject).stream()
                .map(ConnectionSummary::from)
                .toList();
        log.debug("tool broadworks_list_connections returning {} connection(s)", summaries.size());
        return summaries;
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
        // Log non-secret parameters only; the password is never logged.
        log.debug("tool broadworks_add_connection invoked (displayName={}, host={}, port={}, username={}, "
                        + "loginType={}, resourceId={})",
                displayName, hostname, port, username, loginType, resourceId);

        if (hostname == null || hostname.isBlank()) {
            throw new AlpacaException("hostname is required");
        }
        if (port <= 0 || port > 65535) {
            throw new AlpacaException("port must be between 1 and 65535");
        }
        if (!hostAllowlist.isAllowed(hostname)) {
            // Deliberately uniform message: never reveal whether the target exists or is reachable.
            throw new AlpacaException("hostname is not a permitted BroadWorks connection target");
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
        log.debug("tool broadworks_delete_connection invoked (resourceId={})", resourceId);
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
