package co.pitayagroup.mcp.broadworks.config;

import java.util.Locale;
import java.util.Set;

import co.pitayagroup.mcp.broadworks.auth.store.AuthorizationStore;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;
import co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbAuthorizationStore;
import co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbSessionStore;
import co.pitayagroup.mcp.broadworks.auth.store.dynamodb.KmsEncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryAuthorizationStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemoryResourceStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.InMemorySessionStore;
import co.pitayagroup.mcp.broadworks.auth.store.inmemory.NoopEncryptionService;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;

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
 * <p>SAS authorizations share the sessions DynamoDB table (key prefixes avoid collisions) so
 * multi-instance authorize/token exchange works without ALB stickiness.</p>
 *
 * <p>Because {@code IN_MEMORY} also selects {@code NoopEncryptionService} (i.e. no encryption at
 * rest), a missing or empty {@code broadworks.storage.backend} must never silently downgrade a real
 * deployment: {@link #inMemoryBackendGuard} fails startup unless a dev/local/stdio profile is
 * active, the build is running under test, or {@code broadworks.storage.allow-in-memory=true} was
 * set deliberately.</p>
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
    public SessionStore dynamoDbSessionStore(DynamoDbClient client,
                                             StorageProperties properties,
                                             ApplicationIdProperties applicationIdProperties,
                                             EncryptionService encryptionService) {
        return new DynamoDbSessionStore(client, properties.sessionTable(),
                applicationIdProperties.applicationId(), encryptionService);
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend", havingValue = "DYNAMODB")
    public AuthorizationStore dynamoDbAuthorizationStore(DynamoDbClient client,
                                                         StorageProperties properties,
                                                         ApplicationIdProperties applicationIdProperties,
                                                         EncryptionService encryptionService) {
        return new DynamoDbAuthorizationStore(client, properties.sessionTable(),
                applicationIdProperties.applicationId(), encryptionService);
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

    /** Profiles under which the unencrypted, non-durable in-memory backend is acceptable. */
    private static final Set<String> INSECURE_STORAGE_PROFILES = Set.of("dev", "local", "stdio", "test");

    /** Marker bean whose creation fails the context when IN_MEMORY was selected unintentionally. */
    public record InMemoryBackendGuard() {
    }

    @Bean
    @ConditionalOnProperty(prefix = "broadworks.storage", name = "backend",
            havingValue = "IN_MEMORY", matchIfMissing = true)
    public InMemoryBackendGuard inMemoryBackendGuard(Environment environment, StorageProperties properties) {
        // JUnit is never on the runtime classpath of the packaged application.
        final boolean underTest =
                ClassUtils.isPresent("org.junit.jupiter.api.Test", StorageConfig.class.getClassLoader());
        validateInMemoryUsage(environment.getActiveProfiles(), properties.allowInMemory(), underTest);
        return new InMemoryBackendGuard();
    }

    static void validateInMemoryUsage(String[] activeProfiles, boolean allowInMemory, boolean underTest) {
        if (allowInMemory || underTest) {
            return;
        }
        for (String profile : activeProfiles) {
            if (INSECURE_STORAGE_PROFILES.contains(profile.toLowerCase(Locale.ROOT))) {
                return;
            }
        }
        throw new IllegalStateException(
                "broadworks.storage.backend resolved to IN_MEMORY, which stores sessions, OAuth "
                        + "authorizations and BroadWorks credentials unencrypted in a single JVM. "
                        + "Set broadworks.storage.backend=DYNAMODB, activate a dev/local/stdio "
                        + "profile, or set broadworks.storage.allow-in-memory=true to acknowledge.");
    }

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
