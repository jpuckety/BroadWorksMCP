package com.broadworks.mcp.config;

import com.broadworks.mcp.auth.store.AuthorizationStore;
import com.broadworks.mcp.auth.store.EncryptionService;
import com.broadworks.mcp.auth.store.ResourceStore;
import com.broadworks.mcp.auth.store.SessionStore;
import com.broadworks.mcp.auth.store.dynamodb.DynamoDbAuthorizationStore;
import com.broadworks.mcp.auth.store.dynamodb.DynamoDbResourceStore;
import com.broadworks.mcp.auth.store.dynamodb.DynamoDbSessionStore;
import com.broadworks.mcp.auth.store.dynamodb.KmsEncryptionService;
import com.broadworks.mcp.auth.store.inmemory.InMemoryAuthorizationStore;
import com.broadworks.mcp.auth.store.inmemory.InMemoryResourceStore;
import com.broadworks.mcp.auth.store.inmemory.InMemorySessionStore;
import com.broadworks.mcp.auth.store.inmemory.NoopEncryptionService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Wires the pluggable storage layer based on {@code broadworks.storage.backend}.
 *
 * <ul>
 *   <li>{@code DYNAMODB}: durable session / authorization / resource stores with KMS encryption.</li>
 *   <li>{@code IN_MEMORY} (default when unset): non-durable in-memory stores for local / test use.</li>
 * </ul>
 *
 * <p>SAS authorizations and consents share the sessions DynamoDB table (key prefixes avoid
 * collisions) so multi-instance authorize/token exchange works without ALB stickiness.</p>
 */
@Configuration(proxyBeanMethods = false)
public class StorageConfig {

    // ================= DynamoDB backend =================

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public DynamoDbClient dynamoDbClient(StorageProperties properties) {
        final var builder = DynamoDbClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create());
        applyRegion(properties, builder::region);
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public KmsClient kmsClient(StorageProperties properties) {
        final var builder = KmsClient.builder()
                .credentialsProvider(DefaultCredentialsProvider.create());
        applyRegion(properties, builder::region);
        return builder.build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public EncryptionService kmsEncryptionService(KmsClient kmsClient, StorageProperties properties) {
        return new KmsEncryptionService(kmsClient, properties.kmsKeyId());
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public SessionStore dynamoDbSessionStore(DynamoDbClient client, StorageProperties properties) {
        return new DynamoDbSessionStore(client, properties.sessionTable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public AuthorizationStore dynamoDbAuthorizationStore(DynamoDbClient client, StorageProperties properties) {
        return new DynamoDbAuthorizationStore(client, properties.sessionTable());
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public ResourceStore dynamoDbResourceStore(DynamoDbClient client,
                                               StorageProperties properties,
                                               ApplicationIdProperties applicationIdProperties,
                                               EncryptionService encryptionService) {
        return new DynamoDbResourceStore(client, properties.userConfigTable(),
                applicationIdProperties.applicationId(), encryptionService);
    }

    // ================= In-memory backend (default) =================

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend",
            havingValue = "IN_MEMORY", matchIfMissing = true)
    public EncryptionService noopEncryptionService() {
        return new NoopEncryptionService();
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend",
            havingValue = "IN_MEMORY", matchIfMissing = true)
    public SessionStore inMemorySessionStore() {
        return new InMemorySessionStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend",
            havingValue = "IN_MEMORY", matchIfMissing = true)
    public AuthorizationStore inMemoryAuthorizationStore() {
        return new InMemoryAuthorizationStore();
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend",
            havingValue = "IN_MEMORY", matchIfMissing = true)
    public ResourceStore inMemoryResourceStore(EncryptionService encryptionService) {
        return new InMemoryResourceStore(encryptionService);
    }

    private static void applyRegion(StorageProperties properties,
                                    java.util.function.Consumer<Region> regionSetter) {
        if (properties.region() != null && !properties.region().isBlank()) {
            regionSetter.accept(Region.of(properties.region()));
        }
    }
}
