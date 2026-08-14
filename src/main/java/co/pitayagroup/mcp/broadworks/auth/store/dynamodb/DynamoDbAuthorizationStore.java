package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.AUTHORIZATION_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.PK;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.TTL;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.TYPE;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import co.pitayagroup.mcp.broadworks.auth.store.AuthorizationSerialization;
import co.pitayagroup.mcp.broadworks.auth.store.AuthorizationStore;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionContext;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.TokenHashing;

import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.model.TransactWriteItemsEnhancedRequest;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB single-table {@link AuthorizationStore} sharing the sessions table.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code oauth#&lt;id&gt;} — encrypted serialized authorization payload + pointer list</li>
 *   <li>{@code oauthtok#&lt;type&gt;#&lt;sha256(value)&gt;} — reverse pointer to authorization id</li>
 * </ul>
 * Prefixes avoid collisions with the {@code sess#} / {@code client#} keys of
 * {@link DynamoDbSessionStore}. (The interactive HTTP login session has its own table, see
 * {@link DynamoDbHttpSessionRepository}.) Attribute names and value encodings come from
 * {@link DynamoDbItems}, shared with the other stores.</p>
 *
 * <p>Persistence goes through the DynamoDB Enhanced Client: the two item shapes each map to their
 * own bean and {@link TableSchema} view over the same physical table — {@link AuthItem} (the
 * encrypted authorization payload plus its pointer list) and {@link TokenPointerItem} (a reverse
 * token pointer). The atomic delete-stale-pointers + put-payload + put-pointers write uses the
 * enhanced client's {@link DynamoDbEnhancedClient#transactWriteItems transactWriteItems}. The
 * enhanced client is layered over the injected low-level {@link DynamoDbClient}, so a single
 * DynamoDB client bean still backs every store.</p>
 *
 * <p>Token values are hashed before they become part of a key so no replayable credential is ever
 * written, and the payload (which holds the tokens themselves plus the authenticated principal) is
 * encrypted at rest via {@link EncryptionService}, bound to the authorization id.</p>
 */
public class DynamoDbAuthorizationStore implements AuthorizationStore {

    static final String OAUTH_PREFIX = "oauth#";
    static final String TOKEN_PREFIX = "oauthtok#";
    static final String TYPE_AUTH = "oauth-authorization";
    static final String TYPE_TOKEN = "oauth-token-pointer";

    private static final String A_PAYLOAD = "payload";
    private static final String A_TOKEN_TYPE = "tokenType";
    private static final String A_POINTERS = "tokenPointers";

    /** DynamoDB caps a single transactional write at 100 actions. */
    private static final int TRANSACTION_LIMIT = 100;

    private final DynamoDbEnhancedClient enhancedClient;
    private final DynamoDbTable<AuthItem> authTable;
    private final DynamoDbTable<TokenPointerItem> pointerTable;
    private final String applicationId;
    private final EncryptionService encryptionService;

    public DynamoDbAuthorizationStore(DynamoDbClient client, String tableName, String applicationId,
                                      EncryptionService encryptionService) {
        this(DynamoDbEnhancedClient.builder().dynamoDbClient(client).build(), tableName,
                applicationId, encryptionService);
    }

    public DynamoDbAuthorizationStore(DynamoDbEnhancedClient enhancedClient, String tableName,
                                      String applicationId, EncryptionService encryptionService) {
        this.enhancedClient = enhancedClient;
        this.authTable = enhancedClient.table(tableName, TableSchema.fromBean(AuthItem.class));
        this.pointerTable = enhancedClient.table(tableName, TableSchema.fromBean(TokenPointerItem.class));
        this.applicationId = applicationId;
        this.encryptionService = encryptionService;
    }

    @Override
    public void saveAuthorization(OAuth2Authorization authorization) {
        final List<String> newPointers = pointerKeys(authorization);
        final List<String> oldPointers = loadPointerList(authorization.getId());
        final List<Consumer<TransactWriteItemsEnhancedRequest.Builder>> ops = new ArrayList<>();

        for (String old : oldPointers) {
            if (!newPointers.contains(old)) {
                ops.add(b -> b.addDeleteItem(pointerTable, Key.builder().partitionValue(old).build()));
            }
        }

        final Long ttl = DynamoDbItems.ttlEpochSeconds(resolveTtl(authorization));
        final AuthItem authItem = new AuthItem();
        authItem.setPk(OAUTH_PREFIX + authorization.getId());
        authItem.setType(TYPE_AUTH);
        authItem.setAuthorizationId(authorization.getId());
        authItem.setPayload(SdkBytes.fromByteArray(encryptionService.encryptBytes(
                AuthorizationSerialization.serialize(authorization),
                context(authorization.getId()))));
        authItem.setTokenPointers(newPointers);
        authItem.setTtl(ttl);
        ops.add(b -> b.addPutItem(authTable, authItem));

        for (String pointer : newPointers) {
            final TokenPointerItem pointerItem = new TokenPointerItem();
            pointerItem.setPk(pointer);
            pointerItem.setType(TYPE_TOKEN);
            pointerItem.setAuthorizationId(authorization.getId());
            pointerItem.setTokenType(tokenTypeFromPointer(pointer));
            pointerItem.setTtl(ttl);
            ops.add(b -> b.addPutItem(pointerTable, pointerItem));
        }
        writeInBatches(ops);
    }

    @Override
    public void removeAuthorization(OAuth2Authorization authorization) {
        if (authorization == null) {
            return;
        }
        List<String> pointers = loadPointerList(authorization.getId());
        if (pointers.isEmpty()) {
            pointers = new ArrayList<>(pointerKeys(authorization));
        }
        final List<Consumer<TransactWriteItemsEnhancedRequest.Builder>> ops = new ArrayList<>();
        ops.add(b -> b.addDeleteItem(authTable,
                Key.builder().partitionValue(OAUTH_PREFIX + authorization.getId()).build()));
        for (String pointer : pointers) {
            ops.add(b -> b.addDeleteItem(pointerTable, Key.builder().partitionValue(pointer).build()));
        }
        writeInBatches(ops);
    }

    @Override
    public Optional<OAuth2Authorization> findAuthorizationById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        return loadAuthorization(OAUTH_PREFIX + id);
    }

    @Override
    public Optional<OAuth2Authorization> findAuthorizationByToken(String token, @Nullable OAuth2TokenType tokenType) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        if (tokenType == null) {
            for (String type : List.of(
                    "state",
                    "code",
                    OAuth2TokenType.ACCESS_TOKEN.getValue(),
                    OAuth2TokenType.REFRESH_TOKEN.getValue(),
                    "id_token")) {
                final Optional<OAuth2Authorization> found = findByPointer(pointerKey(type, token));
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
        return findByPointer(pointerKey(tokenType.getValue(), token));
    }

    private Optional<OAuth2Authorization> findByPointer(String pointerPk) {
        final TokenPointerItem pointer = pointerTable.getItem(
                Key.builder().partitionValue(pointerPk).build());
        if (pointer == null || pointer.getAuthorizationId() == null) {
            return Optional.empty();
        }
        return loadAuthorization(OAUTH_PREFIX + pointer.getAuthorizationId());
    }

    private Optional<OAuth2Authorization> loadAuthorization(String pk) {
        final AuthItem item = authTable.getItem(Key.builder().partitionValue(pk).build());
        if (item == null || item.getPayload() == null) {
            return Optional.empty();
        }
        final byte[] plaintext = encryptionService.decryptBytes(
                item.getPayload().asByteArray(), context(item.getAuthorizationId()));
        return Optional.ofNullable(
                AuthorizationSerialization.deserialize(plaintext, OAuth2Authorization.class));
    }

    private Map<String, String> context(String authorizationId) {
        return EncryptionContext.forAuthorization(applicationId, authorizationId);
    }

    private List<String> loadPointerList(String authorizationId) {
        final AuthItem item = authTable.getItem(
                Key.builder().partitionValue(OAUTH_PREFIX + authorizationId).build());
        if (item == null || item.getTokenPointers() == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(item.getTokenPointers());
    }

    private static List<String> pointerKeys(OAuth2Authorization authorization) {
        final List<String> keys = new ArrayList<>();
        final String state = authorization.getAttribute("state");
        if (state != null && !state.isBlank()) {
            keys.add(pointerKey("state", state));
        }
        addTokenPointer(keys, "code", authorization.getToken(OAuth2AuthorizationCode.class));
        addTokenPointer(keys, OAuth2TokenType.ACCESS_TOKEN.getValue(), authorization.getAccessToken());
        addTokenPointer(keys, OAuth2TokenType.REFRESH_TOKEN.getValue(), authorization.getRefreshToken());
        addTokenPointer(keys, "id_token", authorization.getToken(OidcIdToken.class));
        return keys;
    }

    private static void addTokenPointer(List<String> keys, String type,
                                        OAuth2Authorization.Token<? extends OAuth2Token> token) {
        if (token != null && token.getToken() != null) {
            keys.add(pointerKey(type, token.getToken().getTokenValue()));
        }
    }

    private static String pointerKey(String type, String value) {
        return TOKEN_PREFIX + type + "#" + TokenHashing.sha256(value);
    }

    private static String tokenTypeFromPointer(String pointer) {
        final String rest = pointer.substring(TOKEN_PREFIX.length());
        final int hash = rest.indexOf('#');
        return hash < 0 ? rest : rest.substring(0, hash);
    }

    private static Instant resolveTtl(OAuth2Authorization authorization) {
        Instant latest = null;
        latest = later(latest, expires(authorization.getAccessToken()));
        latest = later(latest, expires(authorization.getRefreshToken()));
        latest = later(latest, expires(authorization.getToken(OAuth2AuthorizationCode.class)));
        latest = later(latest, expires(authorization.getToken(OidcIdToken.class)));
        // Pending authorizations without tokens still need a short TTL (auth code window).
        return latest != null ? latest : Instant.now().plusSeconds(900L);
    }

    private static Instant expires(OAuth2Authorization.Token<? extends OAuth2Token> token) {
        return token == null || token.getToken() == null ? null : token.getToken().getExpiresAt();
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private void writeInBatches(List<Consumer<TransactWriteItemsEnhancedRequest.Builder>> ops) {
        if (ops.isEmpty()) {
            return;
        }
        for (int i = 0; i < ops.size(); i += TRANSACTION_LIMIT) {
            final List<Consumer<TransactWriteItemsEnhancedRequest.Builder>> batch =
                    ops.subList(i, Math.min(i + TRANSACTION_LIMIT, ops.size()));
            final TransactWriteItemsEnhancedRequest.Builder builder =
                    TransactWriteItemsEnhancedRequest.builder();
            batch.forEach(op -> op.accept(builder));
            enhancedClient.transactWriteItems(builder.build());
        }
    }

    // ---- item beans ------------------------------------------------------

    /**
     * The authorization item ({@code oauth#<id>}): the encrypted, JDK-serialized
     * {@link OAuth2Authorization} payload plus the list of reverse-pointer keys that resolve tokens
     * back to this authorization.
     */
    @DynamoDbBean
    public static class AuthItem {

        private String pk;
        private String type;
        private String authorizationId;
        private SdkBytes payload;
        private List<String> tokenPointers;
        private Long ttl;

        @DynamoDbPartitionKey
        @DynamoDbAttribute(PK)
        public String getPk() {
            return pk;
        }

        public void setPk(String pk) {
            this.pk = pk;
        }

        @DynamoDbAttribute(TYPE)
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @DynamoDbAttribute(AUTHORIZATION_ID)
        public String getAuthorizationId() {
            return authorizationId;
        }

        public void setAuthorizationId(String authorizationId) {
            this.authorizationId = authorizationId;
        }

        @DynamoDbAttribute(A_PAYLOAD)
        public SdkBytes getPayload() {
            return payload;
        }

        public void setPayload(SdkBytes payload) {
            this.payload = payload;
        }

        @DynamoDbAttribute(A_POINTERS)
        public List<String> getTokenPointers() {
            return tokenPointers;
        }

        public void setTokenPointers(List<String> tokenPointers) {
            this.tokenPointers = tokenPointers;
        }

        @DynamoDbAttribute(TTL)
        public Long getTtl() {
            return ttl;
        }

        public void setTtl(Long ttl) {
            this.ttl = ttl;
        }
    }

    /**
     * A reverse token pointer item ({@code oauthtok#<type>#<sha256(value)>}) mapping a hashed token
     * back to its owning authorization id.
     */
    @DynamoDbBean
    public static class TokenPointerItem {

        private String pk;
        private String type;
        private String authorizationId;
        private String tokenType;
        private Long ttl;

        @DynamoDbPartitionKey
        @DynamoDbAttribute(PK)
        public String getPk() {
            return pk;
        }

        public void setPk(String pk) {
            this.pk = pk;
        }

        @DynamoDbAttribute(TYPE)
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @DynamoDbAttribute(AUTHORIZATION_ID)
        public String getAuthorizationId() {
            return authorizationId;
        }

        public void setAuthorizationId(String authorizationId) {
            this.authorizationId = authorizationId;
        }

        @DynamoDbAttribute(A_TOKEN_TYPE)
        public String getTokenType() {
            return tokenType;
        }

        public void setTokenType(String tokenType) {
            this.tokenType = tokenType;
        }

        @DynamoDbAttribute(TTL)
        public Long getTtl() {
            return ttl;
        }

        public void setTtl(Long ttl) {
            this.ttl = ttl;
        }
    }
}
