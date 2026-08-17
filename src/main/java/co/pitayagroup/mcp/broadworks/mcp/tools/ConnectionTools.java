package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.config.PublicBaseUrlProperties;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;
import co.pitayagroup.mcp.broadworks.mcp.model.AddConnectionResult;
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

    private final ResourceStore resourceStore;
    private final HostAllowlist hostAllowlist;
    private final PublicBaseUrlProperties publicBaseUrl;

    @Autowired
    public ConnectionTools(ResourceStore resourceStore, HostAllowlist hostAllowlist,
            PublicBaseUrlProperties publicBaseUrl) {
        this.resourceStore = resourceStore;
        this.hostAllowlist = hostAllowlist;
        this.publicBaseUrl = publicBaseUrl;
    }

    /**
     * Convenience constructor for contexts that carry no public-hostname configuration; the portal
     * URL then falls back to {@link PublicBaseUrlProperties#DEFAULT_BASE_URL} (local dev).
     */
    public ConnectionTools(ResourceStore resourceStore, HostAllowlist hostAllowlist) {
        this(resourceStore, hostAllowlist, new PublicBaseUrlProperties(null));
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
                    + "IMPORTANT: Do NOT ask the user for a password and do NOT attempt to provide, "
                    + "include, or pass a password to this tool. This tool never accepts or stores a "
                    + "password: the connection is saved without one and cannot be used until the user "
                    + "sets the password in the web portal. After calling this, tell the user to open the "
                    + "web portal and set the password for the connection, and give them the exact 'portalUrl' "
                    + "returned in the response. "
                    + "Returns the stored connection summary (needsPassword=true until the password is set), "
                    + "the 'portalUrl' deep link to the password page, and a ready-to-relay 'message'.")
    public AddConnectionResult addConnection(
            @ToolParam(description = "Human-friendly name / nickname for the connection, e.g. 'ECG Production'")
            String displayName,
            @ToolParam(description = "BroadWorks OCI hostname (no scheme or path), e.g. portal.example.com")
            String hostname,
            @ToolParam(description = "BroadWorks OCI port, e.g. 2208") int port,
            @ToolParam(description = "BroadWorks login username") String username,
            @ToolParam(required = false,
                    description = "Explicit resource id to create/replace; when omitted a stable id is "
                            + "derived from the display name")
            String resourceId) {
        final String subject = currentSubject();
        // Log non-secret parameters only. No password is accepted by this tool.
        log.debug("tool broadworks_add_connection invoked (displayName={}, host={}, port={}, username={}, "
                        + "resourceId={})",
                displayName, hostname, port, username, resourceId);

        ConnectionValidation.validate(hostAllowlist, hostname, port, username);

        final String effectiveResourceId = resolveResourceId(resourceId, displayName);
        final String effectiveDisplayName = (displayName == null || displayName.isBlank())
                ? effectiveResourceId : displayName;

        // No password is collected here: the connection is stored password-less and must be finished
        // in the web portal. The password is left null (not an empty string) so the encryption layer
        // skips it entirely -- an empty string would be rejected by KMS, which will not encrypt a
        // zero-length value. A null/blank password is what marks the connection as needing one.
        final AlpacaResource resource = new AlpacaResource(
                effectiveResourceId,
                effectiveDisplayName,
                hostname.trim(),
                port,
                username,
                null);
        resourceStore.put(subject, resource);
        log.info("Stored BroadWorks connection resourceId={} host={} (no password yet; "
                        + "user must set it in the web portal)",
                effectiveResourceId, resource.hostname());

        final ConnectionSummary summary = ConnectionSummary.from(resource);
        final String portalUrl = passwordPortalUrl(effectiveResourceId);
        final String message = "Connection '" + effectiveDisplayName + "' was saved without a password "
                + "and cannot be used yet. Open the web portal to set the password: " + portalUrl;
        return new AddConnectionResult(summary, portalUrl, message);
    }

    /**
     * Deep link to the web-portal page where the password for the given connection is set
     * ({@code <baseUrl>/portal/<resourceId>/password}). The base URL is the server's own externally
     * reachable address, so this is a URL the end user can open directly.
     */
    private String passwordPortalUrl(String resourceId) {
        return publicBaseUrl.baseUrl() + "/portal/" + resourceId + "/password";
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
