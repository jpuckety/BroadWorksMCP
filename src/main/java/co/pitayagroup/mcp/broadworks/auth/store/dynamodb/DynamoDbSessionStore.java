package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.AUTHORIZATION_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.CLIENT_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.CREATED_AT;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.EXPIRES_AT;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.PK;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.SESSION_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.TTL;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.TYPE;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.store.EncryptionContext;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.RegisteredClientRecord;
import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;
import co.pitayagroup.mcp.broadworks.auth.store.TokenHashing;

import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbSecondaryPartitionKey;
import software.amazon.awssdk.enhanced.dynamodb.model.QueryConditional;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

/**
 * DynamoDB single-table {@link SessionStore}.
 *
 * <p>Layout: partition key {@code pk} carries {@code sess#<sessionIdHash>} or
 * {@code client#<clientId>} prefixes with a {@code type} discriminator. Because the opaque-token
 * model uses the access token as the session id, {@code getSessionByAccessToken} is a direct
 * {@code GetItem}. Lookup by refresh token uses the {@code refresh-index} GSI (partition key
 * {@code refreshToken}). Token rotation uses reverse pointer items
 * {@code authz-sess#<authorizationId>} (no extra GSI). A numeric {@code ttl} attribute enables
 * native DynamoDB expiry.</p>
 *
 * <p>Persistence goes through the DynamoDB Enhanced Client. The three item shapes that share the
 * table each map to their own bean and {@link TableSchema} view over the same physical table:
 * {@link SessionItem} (the session, which also owns the {@code refresh-index} secondary key),
 * {@link ClientItem} (a registered client) and {@link PointerItem} (the authorization&rarr;session
 * rotation pointer). The enhanced client is layered over the injected low-level
 * {@link DynamoDbClient}, so a single DynamoDB client bean still backs every store.</p>
 *
 * <p>Item attribute names and value encodings come from {@link DynamoDbItems}, shared with the other
 * stores, so a timestamp such as {@code createdAt} means the same thing and has the same ISO-8601
 * format in every item of every table. The interactive HTTP login session lives in its own table
 * (see {@link DynamoDbHttpSessionRepository}).</p>
 *
 * <p><b>Bearer credentials are never stored verbatim.</b> Access and refresh tokens are reduced to
 * their SHA-256 digest before they are written or looked up, so a table read (or an export/backup)
 * yields nothing replayable; the {@link Session} values returned by this store therefore carry the
 * lookup <em>digests</em> in {@code sessionId}/{@code accessToken}/{@code refreshToken}, not the
 * original token strings. The upstream OIDC id token and IdP refresh token are encrypted with the
 * {@link EncryptionService} before they are written, bound to the owning subject.</p>
 */
public class DynamoDbSessionStore implements SessionStore {

    static final String SESSION_PREFIX = "sess#";
    static final String CLIENT_PREFIX = "client#";
    static final String AUTHZ_SESS_PREFIX = "authz-sess#";
    static final String REFRESH_INDEX = "refresh-index";

    private static final String TYPE_SESSION = "session";
    private static final String TYPE_CLIENT = "client";
    private static final String TYPE_AUTHZ_POINTER = "authz-session-pointer";

    private static final String A_ACCESS_TOKEN = "accessTokenHash";
    /** GSI partition key attribute; holds the refresh-token digest, never the token itself. */
    private static final String A_REFRESH_TOKEN = "refreshToken";
    private static final String A_SUBJECT = "subject";
    private static final String A_EMAIL = "email";
    private static final String A_ID_TOKEN = "idToken";
    private static final String A_IDP_REFRESH = "idpRefreshToken";
    private static final String A_ACCESS_EXP = "accessTokenExpiresAt";
    private static final String A_REFRESH_EXP = "refreshTokenExpiresAt";
    private static final String A_AUDIENCE = "audience";
    private static final String A_CLIENT_NAME = "clientName";
    private static final String A_REDIRECT_URIS = "redirectUris";
    private static final String A_SCOPES = "scopes";
    private static final String A_GRANT_TYPES = "grantTypes";
    private static final String A_AUTH_METHOD = "tokenEndpointAuthMethod";

    private final DynamoDbTable<SessionItem> sessionTable;
    private final DynamoDbTable<ClientItem> clientTable;
    private final DynamoDbTable<PointerItem> pointerTable;
    private final String applicationId;
    private final EncryptionService encryptionService;

    public DynamoDbSessionStore(DynamoDbClient client, String tableName, String applicationId,
                                EncryptionService encryptionService) {
        this(DynamoDbEnhancedClient.builder().dynamoDbClient(client).build(), tableName,
                applicationId, encryptionService);
    }

    public DynamoDbSessionStore(DynamoDbEnhancedClient enhancedClient, String tableName,
                                String applicationId, EncryptionService encryptionService) {
        this.sessionTable = enhancedClient.table(tableName, TableSchema.fromBean(SessionItem.class));
        this.clientTable = enhancedClient.table(tableName, TableSchema.fromBean(ClientItem.class));
        this.pointerTable = enhancedClient.table(tableName, TableSchema.fromBean(PointerItem.class));
        this.applicationId = applicationId;
        this.encryptionService = encryptionService;
    }

    @Override
    public Session createSession(Session session) {
        final String sessionIdHash = TokenHashing.sha256(session.sessionId());
        if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
            final Optional<String> previousSessionId = loadAuthzSessionPointer(session.authorizationId());
            previousSessionId.ifPresent(prev -> {
                if (!prev.equals(sessionIdHash)) {
                    deleteSessionByHash(prev);
                }
            });
        }

        final Instant ttl = session.refreshTokenExpiresAt() != null
                ? session.refreshTokenExpiresAt()
                : session.accessTokenExpiresAt();

        final SessionItem item = new SessionItem();
        item.setPk(SESSION_PREFIX + sessionIdHash);
        item.setType(TYPE_SESSION);
        item.setSessionId(sessionIdHash);
        item.setAccessTokenHash(TokenHashing.sha256(session.accessToken()));
        item.setRefreshToken(TokenHashing.sha256(session.refreshToken()));
        item.setClientId(session.clientId());
        item.setSubject(session.subject());
        item.setEmail(session.email());
        item.setIdToken(encrypt(session.subject(), session.idToken()));
        item.setIdpRefreshToken(encrypt(session.subject(), session.idpRefreshToken()));
        item.setAccessTokenExpiresAt(DynamoDbItems.format(session.accessTokenExpiresAt()));
        item.setRefreshTokenExpiresAt(DynamoDbItems.format(session.refreshTokenExpiresAt()));
        item.setCreatedAt(DynamoDbItems.format(session.createdAt()));
        item.setAuthorizationId(session.authorizationId());
        item.setAudience(session.audience());
        item.setTtl(DynamoDbItems.ttlEpochSeconds(ttl));
        sessionTable.putItem(item);

        if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
            final PointerItem pointer = new PointerItem();
            pointer.setPk(AUTHZ_SESS_PREFIX + session.authorizationId());
            pointer.setType(TYPE_AUTHZ_POINTER);
            pointer.setSessionId(sessionIdHash);
            pointer.setAuthorizationId(session.authorizationId());
            pointer.setTtl(DynamoDbItems.ttlEpochSeconds(ttl));
            pointerTable.putItem(pointer);
        }
        return session;
    }

    @Override
    public Optional<Session> getSessionByAccessToken(String accessToken) {
        if (accessToken == null) {
            return Optional.empty();
        }
        final SessionItem item = sessionTable.getItem(
                Key.builder().partitionValue(SESSION_PREFIX + TokenHashing.sha256(accessToken)).build());
        return item == null ? Optional.empty() : Optional.of(toSession(item));
    }

    @Override
    public Optional<Session> getSessionByRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            return Optional.empty();
        }
        final Key key = Key.builder().partitionValue(TokenHashing.sha256(refreshToken)).build();
        return sessionTable.index(REFRESH_INDEX)
                .query(QueryConditional.keyEqualTo(key)).stream()
                .flatMap(page -> page.items().stream())
                .findFirst()
                .map(this::toSession);
    }

    @Override
    public void deleteSession(String sessionId) {
        if (sessionId == null) {
            return;
        }
        deleteSessionByHash(TokenHashing.sha256(sessionId));
    }

    @Override
    public void deleteSessionsByAuthorizationId(String authorizationId) {
        if (authorizationId == null) {
            return;
        }
        loadAuthzSessionPointer(authorizationId).ifPresent(this::deleteSessionByHash);
    }

    /** Deletes by the already-hashed session id (pointer items store digests, never raw tokens). */
    private void deleteSessionByHash(String sessionIdHash) {
        final SessionItem existing = sessionTable.getItem(
                Key.builder().partitionValue(SESSION_PREFIX + sessionIdHash).build());
        final String authorizationId = existing == null ? null : existing.getAuthorizationId();
        sessionTable.deleteItem(Key.builder().partitionValue(SESSION_PREFIX + sessionIdHash).build());
        if (authorizationId != null && !authorizationId.isBlank()) {
            final Optional<String> pointed = loadAuthzSessionPointer(authorizationId);
            if (pointed.isPresent() && sessionIdHash.equals(pointed.get())) {
                pointerTable.deleteItem(Key.builder().partitionValue(AUTHZ_SESS_PREFIX + authorizationId).build());
            }
        }
    }

    @Override
    public void saveClient(RegisteredClientRecord clientRecord) {
        final ClientItem item = new ClientItem();
        item.setPk(CLIENT_PREFIX + clientRecord.clientId());
        item.setType(TYPE_CLIENT);
        item.setClientId(clientRecord.clientId());
        item.setClientName(clientRecord.clientName());
        item.setRedirectUris(clientRecord.redirectUris());
        item.setScopes(clientRecord.scopes());
        item.setGrantTypes(clientRecord.grantTypes());
        item.setTokenEndpointAuthMethod(clientRecord.tokenEndpointAuthMethod());
        item.setCreatedAt(DynamoDbItems.format(clientRecord.createdAt()));
        item.setExpiresAt(DynamoDbItems.format(clientRecord.expiresAt()));
        item.setTtl(DynamoDbItems.ttlEpochSeconds(clientRecord.expiresAt()));
        clientTable.putItem(item);
    }

    @Override
    public Optional<RegisteredClientRecord> getClient(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        final ClientItem item = clientTable.getItem(
                Key.builder().partitionValue(CLIENT_PREFIX + clientId).build());
        if (item == null) {
            return Optional.empty();
        }
        final RegisteredClientRecord client = toClient(item);
        if (client.expiresAt() != null && Instant.now().isAfter(client.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(client);
    }

    private Optional<String> loadAuthzSessionPointer(String authorizationId) {
        final PointerItem item = pointerTable.getItem(
                Key.builder().partitionValue(AUTHZ_SESS_PREFIX + authorizationId).build());
        if (item == null) {
            return Optional.empty();
        }
        final String sessionId = item.getSessionId();
        return sessionId == null || sessionId.isBlank() ? Optional.empty() : Optional.of(sessionId);
    }

    private Session toSession(SessionItem item) {
        final String subject = item.getSubject();
        return new Session(
                item.getSessionId(),
                item.getAccessTokenHash(),
                item.getRefreshToken(),
                item.getClientId(),
                subject,
                item.getEmail(),
                decrypt(subject, item.getIdToken()),
                decrypt(subject, item.getIdpRefreshToken()),
                DynamoDbItems.parseInstant(item.getAccessTokenExpiresAt()),
                DynamoDbItems.parseInstant(item.getRefreshTokenExpiresAt()),
                DynamoDbItems.parseInstant(item.getCreatedAt()),
                item.getAuthorizationId(),
                item.getAudience()
        );
    }

    private RegisteredClientRecord toClient(ClientItem item) {
        return new RegisteredClientRecord(
                item.getClientId(),
                item.getClientName(),
                item.getRedirectUris(),
                item.getScopes(),
                item.getGrantTypes(),
                item.getTokenEndpointAuthMethod(),
                DynamoDbItems.parseInstant(item.getCreatedAt()),
                DynamoDbItems.parseInstant(item.getExpiresAt())
        );
    }

    private String encrypt(String subject, String value) {
        return value == null ? null
                : encryptionService.encrypt(value, EncryptionContext.forSession(applicationId, subject));
    }

    private String decrypt(String subject, String value) {
        return value == null ? null
                : encryptionService.decrypt(value, EncryptionContext.forSession(applicationId, subject));
    }

    // ---- item beans ------------------------------------------------------

    /**
     * The OAuth session item ({@code sess#<sessionIdHash>}). It owns the {@code refresh-index}
     * secondary partition key so a refresh token can be resolved to its session in one query.
     */
    @DynamoDbBean
    public static class SessionItem {

        private String pk;
        private String type;
        private String sessionId;
        private String accessTokenHash;
        private String refreshToken;
        private String clientId;
        private String subject;
        private String email;
        private String idToken;
        private String idpRefreshToken;
        private String accessTokenExpiresAt;
        private String refreshTokenExpiresAt;
        private String createdAt;
        private String authorizationId;
        private String audience;
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

        @DynamoDbAttribute(SESSION_ID)
        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        @DynamoDbAttribute(A_ACCESS_TOKEN)
        public String getAccessTokenHash() {
            return accessTokenHash;
        }

        public void setAccessTokenHash(String accessTokenHash) {
            this.accessTokenHash = accessTokenHash;
        }

        @DynamoDbSecondaryPartitionKey(indexNames = REFRESH_INDEX)
        @DynamoDbAttribute(A_REFRESH_TOKEN)
        public String getRefreshToken() {
            return refreshToken;
        }

        public void setRefreshToken(String refreshToken) {
            this.refreshToken = refreshToken;
        }

        @DynamoDbAttribute(CLIENT_ID)
        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        @DynamoDbAttribute(A_SUBJECT)
        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        @DynamoDbAttribute(A_EMAIL)
        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        @DynamoDbAttribute(A_ID_TOKEN)
        public String getIdToken() {
            return idToken;
        }

        public void setIdToken(String idToken) {
            this.idToken = idToken;
        }

        @DynamoDbAttribute(A_IDP_REFRESH)
        public String getIdpRefreshToken() {
            return idpRefreshToken;
        }

        public void setIdpRefreshToken(String idpRefreshToken) {
            this.idpRefreshToken = idpRefreshToken;
        }

        @DynamoDbAttribute(A_ACCESS_EXP)
        public String getAccessTokenExpiresAt() {
            return accessTokenExpiresAt;
        }

        public void setAccessTokenExpiresAt(String accessTokenExpiresAt) {
            this.accessTokenExpiresAt = accessTokenExpiresAt;
        }

        @DynamoDbAttribute(A_REFRESH_EXP)
        public String getRefreshTokenExpiresAt() {
            return refreshTokenExpiresAt;
        }

        public void setRefreshTokenExpiresAt(String refreshTokenExpiresAt) {
            this.refreshTokenExpiresAt = refreshTokenExpiresAt;
        }

        @DynamoDbAttribute(CREATED_AT)
        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        @DynamoDbAttribute(AUTHORIZATION_ID)
        public String getAuthorizationId() {
            return authorizationId;
        }

        public void setAuthorizationId(String authorizationId) {
            this.authorizationId = authorizationId;
        }

        @DynamoDbAttribute(A_AUDIENCE)
        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        @DynamoDbAttribute(TTL)
        public Long getTtl() {
            return ttl;
        }

        public void setTtl(Long ttl) {
            this.ttl = ttl;
        }
    }

    /** A dynamically registered OAuth client item ({@code client#<clientId>}). */
    @DynamoDbBean
    public static class ClientItem {

        private String pk;
        private String type;
        private String clientId;
        private String clientName;
        private List<String> redirectUris;
        private List<String> scopes;
        private List<String> grantTypes;
        private String tokenEndpointAuthMethod;
        private String createdAt;
        private String expiresAt;
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

        @DynamoDbAttribute(CLIENT_ID)
        public String getClientId() {
            return clientId;
        }

        public void setClientId(String clientId) {
            this.clientId = clientId;
        }

        @DynamoDbAttribute(A_CLIENT_NAME)
        public String getClientName() {
            return clientName;
        }

        public void setClientName(String clientName) {
            this.clientName = clientName;
        }

        @DynamoDbAttribute(A_REDIRECT_URIS)
        public List<String> getRedirectUris() {
            return redirectUris;
        }

        public void setRedirectUris(List<String> redirectUris) {
            this.redirectUris = redirectUris;
        }

        @DynamoDbAttribute(A_SCOPES)
        public List<String> getScopes() {
            return scopes;
        }

        public void setScopes(List<String> scopes) {
            this.scopes = scopes;
        }

        @DynamoDbAttribute(A_GRANT_TYPES)
        public List<String> getGrantTypes() {
            return grantTypes;
        }

        public void setGrantTypes(List<String> grantTypes) {
            this.grantTypes = grantTypes;
        }

        @DynamoDbAttribute(A_AUTH_METHOD)
        public String getTokenEndpointAuthMethod() {
            return tokenEndpointAuthMethod;
        }

        public void setTokenEndpointAuthMethod(String tokenEndpointAuthMethod) {
            this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
        }

        @DynamoDbAttribute(CREATED_AT)
        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        @DynamoDbAttribute(EXPIRES_AT)
        public String getExpiresAt() {
            return expiresAt;
        }

        public void setExpiresAt(String expiresAt) {
            this.expiresAt = expiresAt;
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
     * The authorization&rarr;session rotation pointer item ({@code authz-sess#<authorizationId>}).
     * Holds the current session-id digest so a re-authorization can evict the prior session.
     */
    @DynamoDbBean
    public static class PointerItem {

        private String pk;
        private String type;
        private String sessionId;
        private String authorizationId;
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

        @DynamoDbAttribute(SESSION_ID)
        public String getSessionId() {
            return sessionId;
        }

        public void setSessionId(String sessionId) {
            this.sessionId = sessionId;
        }

        @DynamoDbAttribute(AUTHORIZATION_ID)
        public String getAuthorizationId() {
            return authorizationId;
        }

        public void setAuthorizationId(String authorizationId) {
            this.authorizationId = authorizationId;
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
