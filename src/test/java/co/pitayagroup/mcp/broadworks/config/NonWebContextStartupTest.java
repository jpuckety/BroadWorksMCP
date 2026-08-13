package co.pitayagroup.mcp.broadworks.config;

import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.GroupTools;
import co.pitayagroup.mcp.broadworks.mcp.tools.ServiceProviderTools;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the stdio / desktop-client deployment: the application must start as a non-web (SERVLET-less)
 * process. The web-only OAuth authorization-server and resource-server security filter chains require
 * an {@code HttpSecurity} bean that only exists in a servlet web context; if they are not gated to
 * {@code SERVLET} web applications the non-web context fails to refresh and the stdio MCP server never
 * starts, so a connected desktop client sees zero tools.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "broadworks.storage.backend=IN_MEMORY",
                // Keep the MCP server beans out of this context-load check so the test does not take
                // over stdin/stdout; the fix under test concerns the security beans created during
                // context refresh, independent of the MCP transport.
                "spring.ai.mcp.server.enabled=false"
        })
class NonWebContextStartupTest {

    @Autowired
    private ApplicationContext context;

    @Test
    void nonWebContextStartsAndExposesToolBeans() {
        assertThat(context.getBean(ConnectionTools.class)).isNotNull();
        assertThat(context.getBean(ServiceProviderTools.class)).isNotNull();
        assertThat(context.getBean(GroupTools.class)).isNotNull();
    }
}
