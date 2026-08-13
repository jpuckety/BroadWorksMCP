package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionContext;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

/**
 * DynamoDB {@link ResourceStore}.
 *
 * <p>Layout: partition key {@code applicationId}, sort key {@code <subject>#<resourceId>}. The
 * secret {@code password} field is encrypted at rest via the injected {@link EncryptionService} and
 * decrypted on read; callers always work with plaintext {@link AlpacaResource} values. The
 * encryption context binds each ciphertext to its {@code applicationId}/{@code subject}/
 * {@code resourceId}, so a relocated blob will not decrypt. Strict tenant isolation is enforced by
 * scoping every query to a {@code subject} prefix.</p>
 */
public class DynamoDbResourceStore implements ResourceStore {

    static final String PK = "applicationId";
    static final String SK = "sk";

    private static final String A_RESOURCE_ID = "resourceId";
    private static final String A_DISPLAY_NAME = "displayName";
    private static final String A_HOSTNAME = "hostname";
    private static final String A_PORT = "port";
    private static final String A_LOGIN_TYPE = "loginType";
    private static final String A_USERNAME = "username";
    private static final String A_PASSWORD = "password";
    private static final String A_USE_PRIVATE_AS = "usePrivateApplicationServerAddress";

    private final DynamoDbClient client;
    private final String tableName;
    private final String applicationId;
    private final EncryptionService encryptionService;

    public DynamoDbResourceStore(DynamoDbClient client, String tableName, String applicationId,
                                 EncryptionService encryptionService) {
        this.client = client;
        this.tableName = tableName;
        this.applicationId = applicationId;
        this.encryptionService = encryptionService;
    }

    @Override
    public List<AlpacaResource> listForUser(String subject) {
        final QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :prefix)")
                .expressionAttributeNames(Map.of("#pk", PK, "#sk", SK))
                .expressionAttributeValues(Map.of(
                        ":pk", s(applicationId),
                        ":prefix", s(subject + "#")))
                .build());
        return response.items().stream().map(item -> toResourceDecrypted(subject, item)).toList();
    }

    @Override
    public Optional<AlpacaResource> get(String subject, String resourceId) {
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key(subject, resourceId))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toResourceDecrypted(subject, response.item()));
    }

    @Override
    public void put(String subject, AlpacaResource resource) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(applicationId));
        item.put(SK, s(subject + "#" + resource.resourceId()));
        item.put(A_RESOURCE_ID, s(resource.resourceId()));
        putIfPresent(item, A_DISPLAY_NAME, resource.displayName());
        putIfPresent(item, A_HOSTNAME, resource.hostname());
        item.put(A_PORT, n(Integer.toString(resource.port())));
        putIfPresent(item, A_LOGIN_TYPE, resource.loginType());
        putIfPresent(item, A_USERNAME, resource.username());
        // Encrypt the secret before persisting, bound to this application/subject/resource.
        putIfPresent(item, A_PASSWORD, encryptionService.encrypt(resource.password(),
                EncryptionContext.forResource(applicationId, subject, resource.resourceId())));
        item.put(A_USE_PRIVATE_AS, AttributeValue.builder()
                .bool(resource.usePrivateApplicationServerAddress()).build());
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    @Override
    public void delete(String subject, String resourceId) {
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key(subject, resourceId))
                .build());
    }

    // ---- helpers ---------------------------------------------------------

    private Map<String, AttributeValue> key(String subject, String resourceId) {
        return Map.of(PK, s(applicationId), SK, s(subject + "#" + resourceId));
    }

    private AlpacaResource toResourceDecrypted(String subject, Map<String, AttributeValue> item) {
        final String resourceId = str(item, A_RESOURCE_ID);
        return new AlpacaResource(
                resourceId,
                str(item, A_DISPLAY_NAME),
                str(item, A_HOSTNAME),
                intVal(item, A_PORT),
                str(item, A_LOGIN_TYPE),
                str(item, A_USERNAME),
                encryptionService.decrypt(str(item, A_PASSWORD),
                        EncryptionContext.forResource(applicationId, subject, resourceId)),
                boolVal(item, A_USE_PRIVATE_AS)
        );
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) {
            item.put(key, s(value));
        }
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static int intVal(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        return value == null || value.n() == null ? 0 : Integer.parseInt(value.n());
    }

    private static boolean boolVal(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        return value != null && Boolean.TRUE.equals(value.bool());
    }
}
