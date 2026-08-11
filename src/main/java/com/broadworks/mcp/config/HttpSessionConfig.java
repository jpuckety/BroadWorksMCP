package com.broadworks.mcp.config;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

import com.broadworks.mcp.auth.store.dynamodb.DynamoDbHttpSessionRepository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.session.SessionRepository;
import org.springframework.session.config.annotation.web.http.EnableSpringHttpSession;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * Enables Spring Session so the interactive Google-login HTTP session (Spring Security
 * {@code SecurityContext}, the transient OAuth2 authorization request, and the saved request) is
 * managed by a {@link SessionRepository} rather than the servlet container's per-task in-memory
 * session.
 *
 * <ul>
 *   <li>{@code broadworks.storage.backend=DYNAMODB}: a {@link DynamoDbHttpSessionRepository} shares
 *       the session across all load-balanced ECS tasks and persists it through restarts, so the
 *       sign-in / authorization-code handshake no longer depends on ALB cookie stickiness (which
 *       native MCP clients do not honor and which is lost on redeploy).</li>
 *   <li>{@code IN_MEMORY} (default, local / stdio / tests): a plain in-memory
 *       {@link MapSessionRepository}, matching the previous non-durable behaviour.</li>
 * </ul>
 *
 * <p>{@link EnableSpringHttpSession} requires exactly one {@link SessionRepository} bean; the two
 * beans below are mutually exclusive on the storage backend so precisely one is active.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableSpringHttpSession
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HttpSessionConfig {

    /** Inactivity window for the interactive login session (mirrors the servlet default of 30m). */
    private static final Duration DEFAULT_MAX_INACTIVE_INTERVAL = Duration.ofMinutes(30);

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public SessionRepository<MapSession> dynamoDbHttpSessionRepository(DynamoDbClient client,
                                                                       StorageProperties properties) {
        return new DynamoDbHttpSessionRepository(client, properties.sessionTable(), DEFAULT_MAX_INACTIVE_INTERVAL);
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend",
            havingValue = "IN_MEMORY", matchIfMissing = true)
    public SessionRepository<MapSession> inMemoryHttpSessionRepository() {
        final MapSessionRepository repository = new MapSessionRepository(new ConcurrentHashMap<>());
        repository.setDefaultMaxInactiveInterval(DEFAULT_MAX_INACTIVE_INTERVAL);
        return repository;
    }
}
