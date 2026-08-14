package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import java.util.List;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionContext;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSortKey;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB {@link ResourceStore}.
 *
 * <p>Layout: partition key {@code applicationId}, sort key {@code <subject>#<resourceId>}. The
 * secret {@code password} field is encrypted at rest via the injected {@link EncryptionService} and
 * decrypted on read; callers always work with plaintext {@link AlpacaResource} values. The
 * encryption context binds each ciphertext to its {@code applicationId}/{@code subject}/
 * {@code resourceId}, so a relocated blob will not decrypt. Strict tenant isolation is enforced by
 * scoping every query to a {@code subject} prefix.</p>
 *
 * <p>Persistence goes through the DynamoDB Enhanced Client: items are mapped to and from the
 * {@link ResourceItem} bean via a {@link TableSchema} rather than hand-built attribute maps, and
 * {@code listForUser} becomes a {@link QueryConditional#sortBeginsWith begins-with} query on the
 * sort key. The enhanced client is layered over the injected low-level {@link DynamoDbClient}, so a
 * single DynamoDB client bean still backs every store.</p>
 */
public class DynamoDbResourceStore implements ResourceStore {

    static final String PK = "applicationId";
    static final String SK = "sk";

    private static final String A_RESOURCE_ID = "resourceId";
    private static final String A_DISPLAY_NAME = "displayName";
    private static final String A_HOSTNAME = "hostname";
    private static final String A_PORT = "port";
    private static final String A_USERNAME = "username";
    private static final String A_PASSWORD = "password";
    private static final String A_USE_PRIVATE_AS = "usePrivateApplicationServerAddress";

    private final DynamoDbTable<ResourceItem> table;
    private final String applicationId;
    private final EncryptionService encryptionService;

    public DynamoDbResourceStore(DynamoDbClient client, String tableName, String applicationId,
                                 EncryptionService encryptionService) {
        this(DynamoDbEnhancedClient.builder().dynamoDbClient(client).build(), tableName,
                applicationId, encryptionService);
    }

    public DynamoDbResourceStore(DynamoDbEnhancedClient enhancedClient, String tableName,
                                 String applicationId, EncryptionService encryptionService) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(ResourceItem.class));
        this.applicationId = applicationId;
        this.encryptionService = encryptionService;
    }

    @Override
    public List<AlpacaResource> listForUser(String subject) {
        final Key prefix = Key.builder()
                .partitionValue(applicationId)
                .sortValue(subject + "#")
                .build();
        return table.query(QueryConditional.sortBeginsWith(prefix)).items().stream()
                .map(item -> toResourceDecrypted(subject, item))
                .toList();
    }

    @Override
    public Optional<AlpacaResource> get(String subject, String resourceId) {
        final ResourceItem item = table.getItem(key(subject, resourceId));
        return item == null ? Optional.empty() : Optional.of(toResourceDecrypted(subject, item));
    }

    @Override
    public void put(String subject, AlpacaResource resource) {
        final ResourceItem item = new ResourceItem();
        item.setApplicationId(applicationId);
        item.setSk(subject + "#" + resource.resourceId());
        item.setResourceId(resource.resourceId());
        item.setDisplayName(resource.displayName());
        item.setHostname(resource.hostname());
        item.setPort(resource.port());
        item.setUsername(resource.username());
        // Encrypt the secret before persisting, bound to this application/subject/resource.
        item.setPassword(encryptionService.encrypt(resource.password(),
                EncryptionContext.forResource(applicationId, subject, resource.resourceId())));
        item.setUsePrivateApplicationServerAddress(resource.usePrivateApplicationServerAddress());
        table.putItem(item);
    }

    @Override
    public void delete(String subject, String resourceId) {
        table.deleteItem(key(subject, resourceId));
    }

    // ---- helpers ---------------------------------------------------------

    private Key key(String subject, String resourceId) {
        return Key.builder()
                .partitionValue(applicationId)
                .sortValue(subject + "#" + resourceId)
                .build();
    }

    private AlpacaResource toResourceDecrypted(String subject, ResourceItem item) {
        final String resourceId = item.getResourceId();
        return new AlpacaResource(
                resourceId,
                item.getDisplayName(),
                item.getHostname(),
                item.getPort(),
                item.getUsername(),
                encryptionService.decrypt(item.getPassword(),
                        EncryptionContext.forResource(applicationId, subject, resourceId)),
                item.isUsePrivateApplicationServerAddress()
        );
    }

    /**
     * Enhanced-client bean mapping a per-user resource item. The composite key is
     * {@code applicationId} (partition) + {@code sk = <subject>#<resourceId>} (sort). The
     * {@code password} is stored as the {@link EncryptionService} ciphertext, never plaintext.
     */
    @DynamoDbBean
    public static class ResourceItem {

        private String applicationId;
        private String sk;
        private String resourceId;
        private String displayName;
        private String hostname;
        private int port;
        private String username;
        private String password;
        private boolean usePrivateApplicationServerAddress;

        @DynamoDbPartitionKey
        @DynamoDbAttribute(PK)
        public String getApplicationId() {
            return applicationId;
        }

        public void setApplicationId(String applicationId) {
            this.applicationId = applicationId;
        }

        @DynamoDbSortKey
        @DynamoDbAttribute(SK)
        public String getSk() {
            return sk;
        }

        public void setSk(String sk) {
            this.sk = sk;
        }

        @DynamoDbAttribute(A_RESOURCE_ID)
        public String getResourceId() {
            return resourceId;
        }

        public void setResourceId(String resourceId) {
            this.resourceId = resourceId;
        }

        @DynamoDbAttribute(A_DISPLAY_NAME)
        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        @DynamoDbAttribute(A_HOSTNAME)
        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        @DynamoDbAttribute(A_PORT)
        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        @DynamoDbAttribute(A_USERNAME)
        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        @DynamoDbAttribute(A_PASSWORD)
        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        @DynamoDbAttribute(A_USE_PRIVATE_AS)
        public boolean isUsePrivateApplicationServerAddress() {
            return usePrivateApplicationServerAddress;
        }

        public void setUsePrivateApplicationServerAddress(boolean usePrivateApplicationServerAddress) {
            this.usePrivateApplicationServerAddress = usePrivateApplicationServerAddress;
        }
    }
}
