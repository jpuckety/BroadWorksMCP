package co.pitayagroup.mcp.broadworks.mcp.tools;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

/**
 * Read-only MCP tool that returns a concise BroadWorks object-model reference for agents.
 *
 * <p>This does not call BroadWorks and has no required parameters, so it does not elicit.
 * The description and returned text both state that the tool is a static reference.</p>
 */
@Component
public class DomainModelTools {

    static final String DESCRIPTION =
            "Returns a concise reference of the BroadWorks object model, hierarchy, Access Devices, "
                    + "Service Packs, and authorization vs assignment rules. Call this when you need to "
                    + "understand relationships between Service Providers, Groups, Users, Access Devices, or Services.";

    static final String DOMAIN_MODEL = """
            # BroadWorks object model (static reference)

            Read-only. No BroadWorks connection or ids required.

            ## Hierarchy
            System → Service Provider / Enterprise → Group → User
            - Service Provider (or Enterprise) owns Groups and Service Packs.
            - Groups contain Users and are the main unit for service assignment and day-to-day provisioning.
            - Users are leaf nodes (individual lines/subscribers).
            - Enterprises and Service Providers are similar at the top level; `isEnterprise` distinguishes them.

            ## Access Devices
            - Groups may contain Access Devices (phones, soft clients, ATA, etc.).
            - Access Device ↔ User is many-to-many: one device can serve many users, and one user can have many devices.
            - Device assignment and Identity/Device Profile management typically happen at Group or User scope — not System.

            ## Services and licensing
            - Service Packs are named bundles of User Services defined on the Service Provider.
            - Prefer assigning a Service Pack over individual services when a pack covers the need.
            - Authorization flows downward: Service Provider → Group → User (or Service Pack).
            - Authorized ≠ Assigned. A service or pack must be authorized at a scope before it can be assigned there.
            - Use authorization tools (`…_service_authorization`) to grant capacity; use assign/unassign tools to attach features.

            ## Scoping
            - Most read/write calls need `serviceProviderId` + `groupId`.
            - User-level calls use `userId` (still confirm the user belongs to the intended group).
            - Optional `resourceId` selects which BroadWorks connection to use; it is not a BroadWorks object id.
            - Always confirm scope (SP vs Group vs User) before create/modify/assign.

            ## Avoid incorrect tool calls
            - List/get first; do not guess ids.
            - Do not assign a service that is not authorized at that scope.
            - Connection tools manage this MCP user's saved servers, not BroadWorks hierarchy objects.
            """;

    @McpTool(name = "broadworks_get_domain_model", description = DESCRIPTION)
    public String getDomainModel() {
        return DOMAIN_MODEL;
    }
}
