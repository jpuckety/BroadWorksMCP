package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.AUTHORIZATION_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.CLIENT_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.CREATED_AT;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.EXPIRES_AT;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.PK;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.SESSION_ID;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.TYPE;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.instant;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.putIfPresent;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.putInstant;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.putTtl;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.s;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.str;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.strList;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.stringList;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import co.pitayagroup.mcp.broadworks.auth.store.EncryptionContext;
import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;
import co.pitayagroup.mcp.broadworks.auth.store.RegisteredClientRecord;
import co.pitayagroup.mcp.broadworks.auth.store.Session;
import co.pitayagroup.mcp.broadworks.auth.store.SessionStore;
import co.pitayagroup.mcp.broadworks.auth.store.TokenHashing;

import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;

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

    private final DynamoDbClient client;
    private final String tableName;
    private final String applicationId;
    private final EncryptionService encryptionService;

    public DynamoDbSessionStore(DynamoDbClient client, String tableName, String applicationId,
                                EncryptionService encryptionService) {
        this.client = client;
        this.tableName = tableName;
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

        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(SESSION_PREFIX + sessionIdHash));
        item.put(TYPE, s("session"));
        item.put(SESSION_ID, s(sessionIdHash));
        item.put(A_ACCESS_TOKEN, s(TokenHashing.sha256(session.accessToken())));
        putIfPresent(item, A_REFRESH_TOKEN, TokenHashing.sha256(session.refreshToken()));
        putIfPresent(item, CLIENT_ID, session.clientId());
        item.put(A_SUBJECT, s(session.subject()));
        putIfPresent(item, A_EMAIL, session.email());
        putIfPresent(item, A_ID_TOKEN, encrypt(session.subject(), session.idToken()));
        putIfPresent(item, A_IDP_REFRESH, encrypt(session.subject(), session.idpRefreshToken()));
        putInstant(item, A_ACCESS_EXP, session.accessTokenExpiresAt());
        putInstant(item, A_REFRESH_EXP, session.refreshTokenExpiresAt());
        putInstant(item, CREATED_AT, session.createdAt());
        putIfPresent(item, AUTHORIZATION_ID, session.authorizationId());
        putIfPresent(item, A_AUDIENCE, session.audience());
        final Instant ttl = session.refreshTokenExpiresAt() != null
                ? session.refreshTokenExpiresAt()
                : session.accessTokenExpiresAt();
        putTtl(item, ttl);
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());

        if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
            final Map<String, AttributeValue> pointer = new HashMap<>();
            pointer.put(PK, s(AUTHZ_SESS_PREFIX + session.authorizationId()));
            pointer.put(TYPE, s("authz-session-pointer"));
            pointer.put(SESSION_ID, s(sessionIdHash));
            pointer.put(AUTHORIZATION_ID, s(session.authorizationId()));
            putTtl(pointer, ttl);
            client.putItem(PutItemRequest.builder().tableName(tableName).item(pointer).build());
        }
        return session;
    }

    @Override
    public Optional<Session> getSessionByAccessToken(String accessToken) {
        if (accessToken == null) {
            return Optional.empty();
        }
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(SESSION_PREFIX + TokenHashing.sha256(accessToken))))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(toSession(response.item()));
    }

    @Override
    public Optional<Session> getSessionByRefreshToken(String refreshToken) {
        if (refreshToken == null) {
            return Optional.empty();
        }
        final QueryResponse response = client.query(QueryRequest.builder()
                .tableName(tableName)
                .indexName(REFRESH_INDEX)
                .keyConditionExpression("#rt = :rt")
                .expressionAttributeNames(Map.of("#rt", A_REFRESH_TOKEN))
                .expressionAttributeValues(Map.of(":rt", s(TokenHashing.sha256(refreshToken))))
                .limit(1)
                .build());
        if (response.count() == 0) {
            return Optional.empty();
        }
        return Optional.of(toSession(response.items().get(0)));
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
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(SESSION_PREFIX + sessionIdHash)))
                .build());
        final String authorizationId = response.hasItem() && !response.item().isEmpty()
                ? str(response.item(), AUTHORIZATION_ID)
                : null;
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(SESSION_PREFIX + sessionIdHash)))
                .build());
        if (authorizationId != null && !authorizationId.isBlank()) {
            final Optional<String> pointed = loadAuthzSessionPointer(authorizationId);
            if (pointed.isPresent() && sessionIdHash.equals(pointed.get())) {
                client.deleteItem(DeleteItemRequest.builder()
                        .tableName(tableName)
                        .key(Map.of(PK, s(AUTHZ_SESS_PREFIX + authorizationId)))
                        .build());
            }
        }
    }

    @Override
    public void saveClient(RegisteredClientRecord clientRecord) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(CLIENT_PREFIX + clientRecord.clientId()));
        item.put(TYPE, s("client"));
        item.put(CLIENT_ID, s(clientRecord.clientId()));
        putIfPresent(item, A_CLIENT_NAME, clientRecord.clientName());
        item.put(A_REDIRECT_URIS, stringList(clientRecord.redirectUris()));
        item.put(A_SCOPES, stringList(clientRecord.scopes()));
        item.put(A_GRANT_TYPES, stringList(clientRecord.grantTypes()));
        putIfPresent(item, A_AUTH_METHOD, clientRecord.tokenEndpointAuthMethod());
        putInstant(item, CREATED_AT, clientRecord.createdAt());
        putInstant(item, EXPIRES_AT, clientRecord.expiresAt());
        putTtl(item, clientRecord.expiresAt());
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    @Override
    public Optional<RegisteredClientRecord> getClient(String clientId) {
        if (clientId == null) {
            return Optional.empty();
        }
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(CLIENT_PREFIX + clientId)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        final RegisteredClientRecord client = toClient(response.item());
        if (client.expiresAt() != null && Instant.now().isAfter(client.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(client);
    }

    private Optional<String> loadAuthzSessionPointer(String authorizationId) {
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(AUTHZ_SESS_PREFIX + authorizationId)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        final String sessionId = str(response.item(), SESSION_ID);
        return sessionId == null || sessionId.isBlank() ? Optional.empty() : Optional.of(sessionId);
    }

    private Session toSession(Map<String, AttributeValue> item) {
        final String subject = str(item, A_SUBJECT);
        return new Session(
                str(item, SESSION_ID),
                str(item, A_ACCESS_TOKEN),
                str(item, A_REFRESH_TOKEN),
                str(item, CLIENT_ID),
                subject,
                str(item, A_EMAIL),
                decrypt(subject, str(item, A_ID_TOKEN)),
                decrypt(subject, str(item, A_IDP_REFRESH)),
                instant(item, A_ACCESS_EXP),
                instant(item, A_REFRESH_EXP),
                instant(item, CREATED_AT),
                str(item, AUTHORIZATION_ID),
                str(item, A_AUDIENCE)
        );
    }

    private RegisteredClientRecord toClient(Map<String, AttributeValue> item) {
        return new RegisteredClientRecord(
                str(item, CLIENT_ID),
                str(item, A_CLIENT_NAME),
                strList(item, A_REDIRECT_URIS),
                strList(item, A_SCOPES),
                strList(item, A_GRANT_TYPES),
                str(item, A_AUTH_METHOD),
                instant(item, CREATED_AT),
                instant(item, EXPIRES_AT)
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
}
