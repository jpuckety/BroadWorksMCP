package com.broadworks.mcp.auth.store.dynamodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.broadworks.mcp.auth.store.RegisteredClientRecord;
import com.broadworks.mcp.auth.store.Session;
import com.broadworks.mcp.auth.store.SessionStore;

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
 * <p>Layout: partition key {@code pk} carries {@code sess#<sessionId>} or {@code client#<clientId>}
 * prefixes with a {@code type} discriminator. Because the opaque-token model uses the access token
 * as the session id, {@code getSessionByAccessToken} is a direct {@code GetItem}. Lookup by refresh
 * token uses the {@code refresh-index} GSI (partition key {@code refreshToken}). Token rotation uses
 * reverse pointer items {@code authz-sess#<authorizationId>} (no extra GSI). A numeric {@code ttl}
 * attribute enables native DynamoDB expiry.</p>
 */
public class DynamoDbSessionStore implements SessionStore {

    static final String PK = "pk";
    static final String TYPE = "type";
    static final String SESSION_PREFIX = "sess#";
    static final String CLIENT_PREFIX = "client#";
    static final String AUTHZ_SESS_PREFIX = "authz-sess#";
    static final String REFRESH_INDEX = "refresh-index";

    private static final String A_ACCESS_TOKEN = "accessToken";
    private static final String A_REFRESH_TOKEN = "refreshToken";
    private static final String A_SESSION_ID = "sessionId";
    private static final String A_CLIENT_ID = "clientId";
    private static final String A_SUBJECT = "subject";
    private static final String A_EMAIL = "email";
    private static final String A_ID_TOKEN = "idToken";
    private static final String A_IDP_REFRESH = "idpRefreshToken";
    private static final String A_ACCESS_EXP = "accessTokenExpiresAt";
    private static final String A_REFRESH_EXP = "refreshTokenExpiresAt";
    private static final String A_CREATED_AT = "createdAt";
    private static final String A_AUTH_ID = "authorizationId";
    private static final String A_AUDIENCE = "audience";
    private static final String A_CLIENT_NAME = "clientName";
    private static final String A_REDIRECT_URIS = "redirectUris";
    private static final String A_SCOPES = "scopes";
    private static final String A_GRANT_TYPES = "grantTypes";
    private static final String A_AUTH_METHOD = "tokenEndpointAuthMethod";
    private static final String A_EXPIRES_AT = "expiresAt";
    private static final String A_TTL = "ttl";

    private final DynamoDbClient client;
    private final String tableName;

    public DynamoDbSessionStore(DynamoDbClient client, String tableName) {
        this.client = client;
        this.tableName = tableName;
    }

    @Override
    public Session createSession(Session session) {
        if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
            final Optional<String> previousSessionId = loadAuthzSessionPointer(session.authorizationId());
            previousSessionId.ifPresent(prev -> {
                if (!prev.equals(session.sessionId())) {
                    deleteSession(prev);
                }
            });
        }

        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(SESSION_PREFIX + session.sessionId()));
        item.put(TYPE, s("session"));
        item.put(A_SESSION_ID, s(session.sessionId()));
        item.put(A_ACCESS_TOKEN, s(session.accessToken()));
        putIfPresent(item, A_REFRESH_TOKEN, session.refreshToken());
        putIfPresent(item, A_CLIENT_ID, session.clientId());
        item.put(A_SUBJECT, s(session.subject()));
        putIfPresent(item, A_EMAIL, session.email());
        putIfPresent(item, A_ID_TOKEN, session.idToken());
        putIfPresent(item, A_IDP_REFRESH, session.idpRefreshToken());
        putInstant(item, A_ACCESS_EXP, session.accessTokenExpiresAt());
        putInstant(item, A_REFRESH_EXP, session.refreshTokenExpiresAt());
        putInstant(item, A_CREATED_AT, session.createdAt());
        putIfPresent(item, A_AUTH_ID, session.authorizationId());
        putIfPresent(item, A_AUDIENCE, session.audience());
        final Instant ttl = session.refreshTokenExpiresAt() != null
                ? session.refreshTokenExpiresAt()
                : session.accessTokenExpiresAt();
        if (ttl != null) {
            item.put(A_TTL, n(Long.toString(ttl.getEpochSecond())));
        }
        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());

        if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
            final Map<String, AttributeValue> pointer = new HashMap<>();
            pointer.put(PK, s(AUTHZ_SESS_PREFIX + session.authorizationId()));
            pointer.put(TYPE, s("authz-session-pointer"));
            pointer.put(A_SESSION_ID, s(session.sessionId()));
            pointer.put(A_AUTH_ID, s(session.authorizationId()));
            if (ttl != null) {
                pointer.put(A_TTL, n(Long.toString(ttl.getEpochSecond())));
            }
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
                .key(Map.of(PK, s(SESSION_PREFIX + accessToken)))
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
                .expressionAttributeValues(Map.of(":rt", s(refreshToken)))
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
        final Optional<Session> existing = getSessionByAccessToken(sessionId);
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(SESSION_PREFIX + sessionId)))
                .build());
        existing.ifPresent(session -> {
            if (session.authorizationId() != null && !session.authorizationId().isBlank()) {
                final Optional<String> pointed = loadAuthzSessionPointer(session.authorizationId());
                if (pointed.isPresent() && sessionId.equals(pointed.get())) {
                    client.deleteItem(DeleteItemRequest.builder()
                            .tableName(tableName)
                            .key(Map.of(PK, s(AUTHZ_SESS_PREFIX + session.authorizationId())))
                            .build());
                }
            }
        });
    }

    @Override
    public void deleteSessionsByAuthorizationId(String authorizationId) {
        if (authorizationId == null) {
            return;
        }
        loadAuthzSessionPointer(authorizationId).ifPresent(this::deleteSession);
    }

    @Override
    public void saveClient(RegisteredClientRecord clientRecord) {
        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(CLIENT_PREFIX + clientRecord.clientId()));
        item.put(TYPE, s("client"));
        item.put(A_CLIENT_ID, s(clientRecord.clientId()));
        putIfPresent(item, A_CLIENT_NAME, clientRecord.clientName());
        item.put(A_REDIRECT_URIS, stringList(clientRecord.redirectUris()));
        item.put(A_SCOPES, stringList(clientRecord.scopes()));
        item.put(A_GRANT_TYPES, stringList(clientRecord.grantTypes()));
        putIfPresent(item, A_AUTH_METHOD, clientRecord.tokenEndpointAuthMethod());
        putInstant(item, A_CREATED_AT, clientRecord.createdAt());
        putInstant(item, A_EXPIRES_AT, clientRecord.expiresAt());
        if (clientRecord.expiresAt() != null) {
            item.put(A_TTL, n(Long.toString(clientRecord.expiresAt().getEpochSecond())));
        }
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
        final String sessionId = str(response.item(), A_SESSION_ID);
        return sessionId == null || sessionId.isBlank() ? Optional.empty() : Optional.of(sessionId);
    }

    private Session toSession(Map<String, AttributeValue> item) {
        return new Session(
                str(item, A_SESSION_ID),
                str(item, A_ACCESS_TOKEN),
                str(item, A_REFRESH_TOKEN),
                str(item, A_CLIENT_ID),
                str(item, A_SUBJECT),
                str(item, A_EMAIL),
                str(item, A_ID_TOKEN),
                str(item, A_IDP_REFRESH),
                instant(item, A_ACCESS_EXP),
                instant(item, A_REFRESH_EXP),
                instant(item, A_CREATED_AT),
                str(item, A_AUTH_ID),
                str(item, A_AUDIENCE)
        );
    }

    private RegisteredClientRecord toClient(Map<String, AttributeValue> item) {
        return new RegisteredClientRecord(
                str(item, A_CLIENT_ID),
                str(item, A_CLIENT_NAME),
                strList(item, A_REDIRECT_URIS),
                strList(item, A_SCOPES),
                strList(item, A_GRANT_TYPES),
                str(item, A_AUTH_METHOD),
                instant(item, A_CREATED_AT),
                instant(item, A_EXPIRES_AT)
        );
    }

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private static AttributeValue stringList(List<String> values) {
        final List<AttributeValue> list = new ArrayList<>();
        for (String value : values) {
            list.add(s(value));
        }
        return AttributeValue.builder().l(list).build();
    }

    private static void putIfPresent(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) {
            item.put(key, s(value));
        }
    }

    private static void putInstant(Map<String, AttributeValue> item, String key, Instant value) {
        if (value != null) {
            item.put(key, s(value.toString()));
        }
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    private static Instant instant(Map<String, AttributeValue> item, String key) {
        final String raw = str(item, key);
        return raw == null ? null : Instant.parse(raw);
    }

    private static List<String> strList(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        if (value == null || value.l() == null) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }
}
