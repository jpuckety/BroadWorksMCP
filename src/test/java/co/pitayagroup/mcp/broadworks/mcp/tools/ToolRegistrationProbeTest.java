package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast, context-free guard that every BroadWorks {@code @McpTool} method the annotation scanner
 * exposes is present in the offline catalogue.
 */
class ToolRegistrationProbeTest {

    private static final Class<?>[] TOOL_CLASSES = {
            ConnectionTools.class,
            ServiceProviderTools.class,
            GroupTools.class,
            UserTools.class,
            ServicePackTools.class,
            ServiceTools.class,
            DomainModelTools.class
    };

    @Test
    void allBroadWorksToolsAreDiscovered() {
        final List<String> names = new ArrayList<>();
        for (Class<?> type : TOOL_CLASSES) {
            for (Method method : type.getDeclaredMethods()) {
                final McpTool annotation = method.getAnnotation(McpTool.class);
                if (annotation != null && !annotation.name().isBlank()) {
                    names.add(annotation.name());
                }
            }
        }

        assertThat(names).containsExactlyInAnyOrder(
                "broadworks_add_connection",
                "broadworks_list_connections",
                "broadworks_delete_connection",
                "broadworks_list_service_providers",
                "broadworks_get_service_provider",
                "broadworks_modify_service_provider",
                "broadworks_create_service_provider",
                "broadworks_delete_service_provider",
                "broadworks_list_groups",
                "broadworks_get_group",
                "broadworks_modify_group",
                "broadworks_create_group",
                "broadworks_delete_group",
                "broadworks_list_users",
                "broadworks_get_user",
                "broadworks_modify_user",
                "broadworks_create_user",
                "broadworks_delete_user",
                "broadworks_list_service_packs",
                "broadworks_get_service_pack",
                "broadworks_create_service_pack",
                "broadworks_modify_service_pack",
                "broadworks_delete_service_pack",
                "broadworks_get_service_provider_service_authorization",
                "broadworks_modify_service_provider_service_authorization",
                "broadworks_get_group_service_authorization",
                "broadworks_modify_group_service_authorization",
                "broadworks_assign_group_services",
                "broadworks_unassign_group_services",
                "broadworks_get_user_assigned_services",
                "broadworks_assign_user_services",
                "broadworks_unassign_user_services",
                "broadworks_get_domain_model");
    }

    @Test
    void deleteAndModifyToolsAdvertiseDestructiveHint() {
        final Set<String> destructiveTools = Set.of(
                "broadworks_delete_connection",
                "broadworks_modify_service_provider",
                "broadworks_delete_service_provider",
                "broadworks_modify_group",
                "broadworks_delete_group",
                "broadworks_modify_user",
                "broadworks_delete_user",
                "broadworks_modify_service_pack",
                "broadworks_delete_service_pack",
                "broadworks_modify_service_provider_service_authorization",
                "broadworks_modify_group_service_authorization");

        final List<String> advertised = new ArrayList<>();
        for (Class<?> type : TOOL_CLASSES) {
            for (Method method : type.getDeclaredMethods()) {
                final McpTool annotation = method.getAnnotation(McpTool.class);
                if (annotation == null || annotation.name().isBlank()) {
                    continue;
                }
                if (annotation.name().contains("_delete_") || annotation.name().contains("_modify_")) {
                    advertised.add(annotation.name());
                    assertThat(annotation.annotations().destructiveHint())
                            .as("%s should advertise destructiveHint", annotation.name())
                            .isTrue();
                }
            }
        }

        assertThat(advertised).containsExactlyInAnyOrderElementsOf(destructiveTools);
    }
}
