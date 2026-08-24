package co.pitayagroup.mcp.broadworks.mcp.tools;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.method.tool.utils.McpJsonSchemaGenerator;

import static org.assertj.core.api.Assertions.assertThat;

class DomainModelToolsTest {

    private final DomainModelTools tools = new DomainModelTools();

    @Test
    void returnsConciseMarkdownReference() {
        final String body = tools.getDomainModel();

        assertThat(body)
                .contains("System → Service Provider / Enterprise → Group → User")
                .contains("Access Device ↔ User is many-to-many")
                .contains("Prefer assigning a Service Pack")
                .contains("Authorization flows downward")
                .contains("Authorized ≠ Assigned")
                .contains("serviceProviderId")
                .contains("groupId")
                .contains("userId")
                .contains("resourceId")
                .contains("Read-only");
        assertThat(body.length()).isLessThan(3500);
    }

    @Test
    void registersReadOnlyStyleToolWithNoRequiredParameters() throws Exception {
        final Method method = DomainModelTools.class.getMethod("getDomainModel");
        final McpTool annotation = method.getAnnotation(McpTool.class);
        assertThat(annotation).isNotNull();
        assertThat(annotation.name()).isEqualTo("broadworks_get_domain_model");
        assertThat(annotation.description()).isEqualTo(DomainModelTools.DESCRIPTION);
        assertThat(McpJsonSchemaGenerator.generateForMethodInput(method))
                .contains("\"type\"")
                .doesNotContain("\"required\":[");
    }
}
