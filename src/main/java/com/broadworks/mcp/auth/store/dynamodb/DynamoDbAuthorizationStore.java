package com.broadworks.mcp.auth.store.dynamodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.broadworks.mcp.auth.store.AuthorizationSerialization;
import com.broadworks.mcp.auth.store.AuthorizationStore;
import com.broadworks.mcp.auth.store.EncryptionContext;
import com.broadworks.mcp.auth.store.EncryptionService;
import com.broadworks.mcp.auth.store.TokenHashing;

import org.springframework.lang.Nullable;
import org.springframework.security.oauth2.core.OAuth2Token;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationCode;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;

/**
 * DynamoDB single-table {@link AuthorizationStore} sharing the sessions table.
 *
 * <p>Layout:
 * <ul>
 *   <li>{@code oauth#&lt;id&gt;} — encrypted serialized authorization payload + pointer list</li>
 *   <li>{@code oauthtok#&lt;type&gt;#&lt;sha256(value)&gt;} — reverse pointer to authorization id</li>
 * </ul>
 * Prefixes avoid collisions with {@code sess#} / {@code client#} / HTTP-session keys.</p>
 *
 * <p>Token values are hashed before they become part of a key so no replayable credential is ever
 * written, and the payload (which holds the tokens themselves plus the authenticated principal) is
 * encrypted at rest via {@link EncryptionService}, bound to the authorization id.</p>
 */
public class DynamoDbAuthorizationStore implements AuthorizationStore {

    static final String PK = "pk";
    static final String TYPE = "type";
    static final String OAUTH_PREFIX = "oauth#";
    static final String TOKEN_PREFIX = "oauthtok#";
    static final String TYPE_AUTH = "oauth-authorization";
    static final String TYPE_TOKEN = "oauth-token-pointer";

    private static final String A_AUTH_ID = "authorizationId";
    private static final String A_PAYLOAD = "payload";
    private static final String A_TOKEN_TYPE = "tokenType";
    private static final String A_TTL = "ttl";
    private static final String A_POINTERS = "tokenPointers";

    private final DynamoDbClient client;
    private final String tableName;
    private final String applicationId;
    private final EncryptionService encryptionService;

    public DynamoDbAuthorizationStore(DynamoDbClient client, String tableName, String applicationId,
                                      EncryptionService encryptionService) {
        this.client = client;
        this.tableName = tableName;
        this.applicationId = applicationId;
        this.encryptionService = encryptionService;
    }

    @Override
    public void saveAuthorization(OAuth2Authorization authorization) {
        final List<String> newPointers = pointerKeys(authorization);
        final List<String> oldPointers = loadPointerList(authorization.getId());
        final List<TransactWriteItem> items = new ArrayList<>();

        for (String old : oldPointers) {
            if (!newPointers.contains(old)) {
                items.add(TransactWriteItem.builder()
                        .delete(d -> d.tableName(tableName).key(Map.of(PK, s(old))))
                        .build());
            }
        }

        final Instant ttl = resolveTtl(authorization);
        final Map<String, AttributeValue> authItem = new HashMap<>();
        authItem.put(PK, s(OAUTH_PREFIX + authorization.getId()));
        authItem.put(TYPE, s(TYPE_AUTH));
        authItem.put(A_AUTH_ID, s(authorization.getId()));
        authItem.put(A_PAYLOAD, AttributeValue.builder()
                .b(SdkBytes.fromByteArray(encryptionService.encryptBytes(
                        AuthorizationSerialization.serialize(authorization),
                        context(authorization.getId()))))
                .build());
        authItem.put(A_POINTERS, stringList(newPointers));
        if (ttl != null) {
            authItem.put(A_TTL, n(Long.toString(ttl.getEpochSecond())));
        }
        items.add(TransactWriteItem.builder()
                .put(p -> p.tableName(tableName).item(authItem))
                .build());

        for (String pointer : newPointers) {
            final Map<String, AttributeValue> pointerItem = new HashMap<>();
            pointerItem.put(PK, s(pointer));
            pointerItem.put(TYPE, s(TYPE_TOKEN));
            pointerItem.put(A_AUTH_ID, s(authorization.getId()));
            pointerItem.put(A_TOKEN_TYPE, s(tokenTypeFromPointer(pointer)));
            if (ttl != null) {
                pointerItem.put(A_TTL, n(Long.toString(ttl.getEpochSecond())));
            }
            items.add(TransactWriteItem.builder()
                    .put(p -> p.tableName(tableName).item(pointerItem))
                    .build());
        }
        writeInBatches(items);
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
        final List<TransactWriteItem> items = new ArrayList<>();
        items.add(TransactWriteItem.builder()
                .delete(d -> d.tableName(tableName)
                        .key(Map.of(PK, s(OAUTH_PREFIX + authorization.getId()))))
                .build());
        for (String pointer : pointers) {
            items.add(TransactWriteItem.builder()
                    .delete(d -> d.tableName(tableName).key(Map.of(PK, s(pointer))))
                    .build());
        }
        writeInBatches(items);
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
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(pointerPk)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        final AttributeValue authId = response.item().get(A_AUTH_ID);
        if (authId == null || authId.s() == null) {
            return Optional.empty();
        }
        return loadAuthorization(OAUTH_PREFIX + authId.s());
    }

    private Optional<OAuth2Authorization> loadAuthorization(String pk) {
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(pk)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return Optional.empty();
        }
        final AttributeValue payload = response.item().get(A_PAYLOAD);
        if (payload == null || payload.b() == null) {
            return Optional.empty();
        }
        final String authorizationId = str(response.item(), A_AUTH_ID);
        final byte[] plaintext = encryptionService.decryptBytes(payload.b().asByteArray(), context(authorizationId));
        return Optional.ofNullable(
                AuthorizationSerialization.deserialize(plaintext, OAuth2Authorization.class));
    }

    private Map<String, String> context(String authorizationId) {
        return EncryptionContext.forAuthorization(applicationId, authorizationId);
    }

    private List<String> loadPointerList(String authorizationId) {
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(OAUTH_PREFIX + authorizationId)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return new ArrayList<>();
        }
        final AttributeValue pointers = response.item().get(A_POINTERS);
        if (pointers == null || pointers.l() == null) {
            return new ArrayList<>();
        }
        final List<String> result = new ArrayList<>();
        for (AttributeValue value : pointers.l()) {
            if (value.s() != null) {
                result.add(value.s());
            }
        }
        return result;
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

    private void writeInBatches(List<TransactWriteItem> items) {
        if (items.isEmpty()) {
            return;
        }
        final int batchSize = 100;
        for (int i = 0; i < items.size(); i += batchSize) {
            final List<TransactWriteItem> batch = items.subList(i, Math.min(i + batchSize, items.size()));
            client.transactWriteItems(TransactWriteItemsRequest.builder().transactItems(batch).build());
        }
    }

    private static String str(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        return value == null ? null : value.s();
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
}
