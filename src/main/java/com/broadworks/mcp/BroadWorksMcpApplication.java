package com.broadworks.mcp;

import com.broadworks.mcp.config.AlpacaProperties;
import com.broadworks.mcp.config.ApplicationIdProperties;
import com.broadworks.mcp.config.AuthTokenProperties;
import com.broadworks.mcp.config.OidcProperties;
import com.broadworks.mcp.config.PublicBaseUrlProperties;
import com.broadworks.mcp.config.RedirectAllowlistProperties;
import com.broadworks.mcp.config.StorageProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Entry point for the BroadWorks MCP server.
 *
 * <p>The server runs as a single Spring Boot 3 / Java 21 application that is simultaneously an
 * OAuth 2.1 Authorization Server, a bearer-token Resource Server, and a Spring AI MCP server
 * exposing BroadWorks operations through the Alpaca toolkit.</p>
 *
 * <p>Transports:</p>
 * <ul>
 *   <li>HTTP (default): Streamable HTTP/SSE MCP endpoints on {@code :8080} with the full OAuth
 *       and Resource Server stack active.</li>
 *   <li>stdio ({@code stdio} profile): web server disabled, MCP served over stdin/stdout with all
 *       logging redirected to stderr.</li>
 * </ul>
 */
@SpringBootApplication
@EnableConfigurationProperties({
        OidcProperties.class,
        AuthTokenProperties.class,
        StorageProperties.class,
        AlpacaProperties.class,
        PublicBaseUrlProperties.class,
        RedirectAllowlistProperties.class,
        ApplicationIdProperties.class
})
public class BroadWorksMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(BroadWorksMcpApplication.class, args);
    }
}
