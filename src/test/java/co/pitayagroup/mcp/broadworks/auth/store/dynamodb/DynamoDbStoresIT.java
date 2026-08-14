package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionContext;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.RegisteredClientRecord;
import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.TokenHashing;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyResponse;

/**
 * Integration tests for the DynamoDB-backed stores and KMS encryption, running against LocalStack.
 *
 * <p>The whole class is disabled automatically when Docker is unavailable, keeping {@code mvn test}
 * green in environments without a container runtime.</p>
 */
@Testcontainers(disabledWithoutDocker = true)
class DynamoDbStoresIT {

    private static final String SESSION_TABLE = "sessions";
    private static final String HTTP_SESSION_TABLE = "http-sessions";
    private static final String USER_CONFIG_TABLE = "user-config";
    private static final String APPLICATION_ID = "test-app";

    @Container
    static final LocalStackContainer LOCALSTACK = new LocalStackContainer(
            DockerImageName.parse("localstack/localstack:3.7"))
            .withServices(LocalStackContainer.Service.DYNAMODB, LocalStackContainer.Service.KMS);

    private static DynamoDbClient dynamo;
    private static KmsClient kms;
    private static EncryptionService encryptionService;

    @BeforeAll
    static void setUp() {
        final StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()));
        final Region region = Region.of(LOCALSTACK.getRegion());

        dynamo = DynamoDbClient.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .credentialsProvider(credentials)
                .region(region)
                .build();
        kms = KmsClient.builder()
                .endpointOverride(URI.create(LOCALSTACK.getEndpoint().toString()))
                .credentialsProvider(credentials)
                .region(region)
                .build();

        createSessionTable();
        createHttpSessionTable();
        createUserConfigTable();

        final CreateKeyResponse key = kms.createKey(b -> b.description("test key"));
        encryptionService = new KmsEncryptionService(kms, key.keyMetadata().keyId());
    }

    @AfterAll
    static void tearDown() {
        if (dynamo != null) {
            dynamo.close();
        }
        if (kms != null) {
            kms.close();
        }
    }

    private static void createSessionTable() {
        dynamo.createTable(CreateTableRequest.builder()
                .tableName(SESSION_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("refreshToken").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("refresh-index")
                        .keySchema(KeySchemaElement.builder().attributeName("refreshToken").keyType(KeyType.HASH).build())
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .build());
    }

    /** The interactive login session has its own table, keyed by the plain servlet session id. */
    private static void createHttpSessionTable() {
        dynamo.createTable(CreateTableRequest.builder()
                .tableName(HTTP_SESSION_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("pk").attributeType(ScalarAttributeType.S).build())
                .keySchema(KeySchemaElement.builder().attributeName("pk").keyType(KeyType.HASH).build())
                .build());
    }

    private static void createUserConfigTable() {
        dynamo.createTable(CreateTableRequest.builder()
                .tableName(USER_CONFIG_TABLE)
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .attributeDefinitions(
                        AttributeDefinition.builder().attributeName("applicationId").attributeType(ScalarAttributeType.S).build(),
                        AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build())
                .keySchema(
                        KeySchemaElement.builder().attributeName("applicationId").keyType(KeyType.HASH).build(),
                        KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build())
                .build());
    }

    // ---- session store ---------------------------------------------------

    @Test
    void sessionCrudAndTokenLookups() {
        final DynamoDbSessionStore store = sessionStore();
        final Instant now = Instant.now();
        final Session session = new Session(null, "acc-1", "ref-1", "client-1", "sub-1",
                "sub-1@example.com", "idt", "idpref",
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(30)), now,
                "authz-1", "http://localhost:8080/mcp");

        store.createSession(session);

        assertThat(store.getSessionByAccessToken("acc-1")).isPresent()
                .get().extracting(Session::subject).isEqualTo("sub-1");
        // Lookups still work, but the stored identifiers are digests, not the tokens themselves.
        assertThat(store.getSessionByRefreshToken("ref-1")).isPresent()
                .get().extracting(Session::accessToken).isEqualTo(TokenHashing.sha256("acc-1"));

        store.deleteSession("acc-1");
        assertThat(store.getSessionByAccessToken("acc-1")).isEmpty();
    }

    @Test
    void sessionItemNeverContainsReplayableCredentials() {
        final DynamoDbSessionStore store = sessionStore();
        final Instant now = Instant.now();
        store.createSession(new Session(null, "acc-raw", "ref-raw", "client-1", "sub-raw",
                "sub-raw@example.com", "google-id-token", "google-refresh-token",
                now.plus(Duration.ofHours(1)), now.plus(Duration.ofDays(30)), now,
                "authz-raw", "http://localhost:8080/mcp"));

        final Map<String, AttributeValue> raw = dynamo.getItem(GetItemRequest.builder()
                .tableName(SESSION_TABLE)
                .key(Map.of("pk", AttributeValue.fromS("sess#" + TokenHashing.sha256("acc-raw"))))
                .build()).item();

        assertThat(raw).isNotEmpty();
        assertThat(raw.values())
                .noneMatch(value -> "acc-raw".equals(value.s()) || "ref-raw".equals(value.s())
                        || "google-id-token".equals(value.s()) || "google-refresh-token".equals(value.s()));
        assertThat(raw.get("refreshToken").s()).isEqualTo(TokenHashing.sha256("ref-raw"));

        // ...yet the encrypted upstream tokens round-trip for the owning subject.
        assertThat(store.getSessionByAccessToken("acc-raw")).isPresent()
                .get().extracting(Session::idToken).isEqualTo("google-id-token");
    }

    @Test
    void httpSessionRoundTripAcrossRepositoryInstances() {
        // Two repository instances model the two load-balanced ECS tasks sharing one table.
        final DynamoDbHttpSessionRepository taskA =
                new DynamoDbHttpSessionRepository(dynamo, HTTP_SESSION_TABLE, Duration.ofMinutes(30));
        final DynamoDbHttpSessionRepository taskB =
                new DynamoDbHttpSessionRepository(dynamo, HTTP_SESSION_TABLE, Duration.ofMinutes(30));

        final org.springframework.session.MapSession created = taskA.createSession();
        created.setAttribute("SPRING_SECURITY_CONTEXT", "principal-abc");
        taskA.save(created);

        // A different instance (task) can load the session the first one created.
        final org.springframework.session.MapSession loaded = taskB.findById(created.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.<String>getAttribute("SPRING_SECURITY_CONTEXT")).isEqualTo("principal-abc");

        taskB.deleteById(created.getId());
        assertThat(taskA.findById(created.getId())).isNull();
    }

    @Test
    void clientCrud() {
        final DynamoDbSessionStore store = sessionStore();
        final RegisteredClientRecord client = new RegisteredClientRecord(
                "client-1", "App", List.of("https://a/cb"), List.of("openid"),
                List.of("authorization_code", "refresh_token"), null,
                Instant.now(), Instant.now().plus(Duration.ofDays(90)));

        store.saveClient(client);

        assertThat(store.getClient("client-1")).isPresent()
                .get().satisfies(c -> {
                    assertThat(c.redirectUris()).containsExactly("https://a/cb");
                    assertThat(c.grantTypes()).contains("refresh_token");
                });
    }

    // ---- resource store + KMS -------------------------------------------

    @Test
    void resourceCrudPerSubjectWithEncryptedSecret() {
        final DynamoDbResourceStore store =
                new DynamoDbResourceStore(dynamo, USER_CONFIG_TABLE, APPLICATION_ID, encryptionService);

        store.put("sub-a", new AlpacaResource("res-1", "A", "host-a", 2208, "admin", "pw-a"));
        store.put("sub-a", new AlpacaResource("res-2", "A2", "host-a2", 2208, "admin", "pw-a2"));
        store.put("sub-b", new AlpacaResource("res-1", "B", "host-b", 2208, "user", "pw-b"));

        assertThat(store.get("sub-a", "res-1")).isPresent()
                .get().extracting(AlpacaResource::password).isEqualTo("pw-a");
        assertThat(store.listForUser("sub-a")).extracting(AlpacaResource::resourceId)
                .containsExactlyInAnyOrder("res-1", "res-2");
        assertThat(store.get("sub-b", "res-2")).isEmpty();

        // Password must be stored encrypted (not equal to the plaintext) in the raw item.
        final Map<String, AttributeValue> raw = dynamo.getItem(GetItemRequest.builder()
                .tableName(USER_CONFIG_TABLE)
                .key(Map.of(
                        "applicationId", AttributeValue.fromS(APPLICATION_ID),
                        "sk", AttributeValue.fromS("sub-a#res-1")))
                .build()).item();
        assertThat(raw.get("password").s()).isNotEqualTo("pw-a");

        store.delete("sub-a", "res-1");
        assertThat(store.get("sub-a", "res-1")).isEmpty();
    }

    @Test
    void kmsEncryptionRoundTrip() {
        final Map<String, String> context = EncryptionContext.forResource(APPLICATION_ID, "sub-a", "res-1");
        final String plaintext = "s3cret-value";
        final String ciphertext = encryptionService.encrypt(plaintext, context);
        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(encryptionService.decrypt(ciphertext, context)).isEqualTo(plaintext);
    }

    @Test
    void kmsDecryptRejectsCiphertextMovedToAnotherRecord() {
        final Map<String, String> owner = EncryptionContext.forResource(APPLICATION_ID, "sub-a", "res-1");
        final Map<String, String> other = EncryptionContext.forResource(APPLICATION_ID, "sub-b", "res-1");
        final String ciphertext = encryptionService.encrypt("s3cret-value", owner);

        assertThatThrownBy(() -> encryptionService.decrypt(ciphertext, other))
                .isInstanceOf(Exception.class);
    }

    @Test
    void envelopeEncryptionRoundTripsPayloadsLargerThanTheKmsLimit() {
        final Map<String, String> context = EncryptionContext.forAuthorization(APPLICATION_ID, "authz-1");
        final byte[] plaintext = new byte[32 * 1024];
        new java.util.Random(42).nextBytes(plaintext);

        final byte[] ciphertext = encryptionService.encryptBytes(plaintext, context);
        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(encryptionService.decryptBytes(ciphertext, context)).isEqualTo(plaintext);

        final Map<String, String> other = EncryptionContext.forAuthorization(APPLICATION_ID, "authz-2");
        assertThatThrownBy(() -> encryptionService.decryptBytes(ciphertext, other))
                .isInstanceOf(Exception.class);
    }

    private static DynamoDbSessionStore sessionStore() {
        return new DynamoDbSessionStore(dynamo, SESSION_TABLE, APPLICATION_ID, encryptionService);
    }
}
