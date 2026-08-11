package com.broadworks.mcp.auth.store.dynamodb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

/**
 * DynamoDB-backed Spring Session {@link SessionRepository} for the interactive HTTP login session.
 *
 * <p>Reuses the single sessions table (same {@code pk} partition key and {@code ttl} attribute as
 * {@link DynamoDbSessionStore}), keying HTTP sessions with a {@code httpsess#<id>} prefix so they
 * coexist with the {@code sess#} / {@code client#} items. Because the session lives in DynamoDB
 * rather than a single task's memory, any load-balanced instance can serve any request in the
 * Google sign-in / authorization-code handshake, which removes the need for ALB session
 * stickiness and lets the login session survive task restarts and redeploys.</p>
 *
 * <p>Session metadata (creation / last-accessed / max-inactive) is stored as plain numeric
 * attributes; the session attribute map (which carries the Spring Security {@code SecurityContext},
 * the transient OAuth2 authorization request, and the saved request) is JDK-serialized into a
 * single binary attribute. On load a fresh {@link MapSession} is reconstructed for the id (so its
 * id generator is initialised and {@link MapSession#changeSessionId()} used by session-fixation
 * protection works). A blob that cannot be deserialized (e.g. after an incompatible class change)
 * is treated as an absent session so the user simply re-authenticates.</p>
 */
public class DynamoDbHttpSessionRepository implements SessionRepository<MapSession> {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbHttpSessionRepository.class);

    static final String PK = "pk";
    static final String TYPE = "type";
    static final String HTTP_SESSION_PREFIX = "httpsess#";
    static final String TYPE_VALUE = "http-session";

    private static final String A_CREATION_TIME = "creationTime";
    private static final String A_LAST_ACCESSED = "lastAccessedTime";
    private static final String A_MAX_INACTIVE = "maxInactiveSeconds";
    private static final String A_ATTRIBUTES = "attributes";
    private static final String A_TTL = "ttl";

    private final DynamoDbClient client;
    private final String tableName;
    private final Duration defaultMaxInactiveInterval;

    public DynamoDbHttpSessionRepository(DynamoDbClient client, String tableName,
                                         Duration defaultMaxInactiveInterval) {
        this.client = client;
        this.tableName = tableName;
        this.defaultMaxInactiveInterval = defaultMaxInactiveInterval;
    }

    @Override
    public MapSession createSession() {
        final MapSession session = new MapSession();
        session.setMaxInactiveInterval(this.defaultMaxInactiveInterval);
        return session;
    }

    @Override
    public void save(MapSession session) {
        // Session-fixation protection rotates the id via changeSessionId(); remove the stale item so
        // the old id no longer resolves (the new id is written below).
        if (!session.getId().equals(session.getOriginalId())) {
            deleteById(session.getOriginalId());
        }

        final Map<String, AttributeValue> item = new HashMap<>();
        item.put(PK, s(HTTP_SESSION_PREFIX + session.getId()));
        item.put(TYPE, s(TYPE_VALUE));
        item.put(A_CREATION_TIME, n(session.getCreationTime().toEpochMilli()));
        item.put(A_LAST_ACCESSED, n(session.getLastAccessedTime().toEpochMilli()));
        final long maxInactiveSeconds = session.getMaxInactiveInterval().getSeconds();
        item.put(A_MAX_INACTIVE, n(maxInactiveSeconds));

        final Map<String, Object> attributes = new HashMap<>();
        for (String name : session.getAttributeNames()) {
            attributes.put(name, session.getAttribute(name));
        }
        if (!attributes.isEmpty()) {
            item.put(A_ATTRIBUTES, AttributeValue.builder().b(SdkBytes.fromByteArray(serialize(attributes))).build());
        }

        // Native DynamoDB TTL cleanup: expire at last-access + inactivity window (skip when infinite).
        if (maxInactiveSeconds > 0) {
            final long ttl = session.getLastAccessedTime().plusSeconds(maxInactiveSeconds).getEpochSecond();
            item.put(A_TTL, n(ttl));
        }

        client.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    @Override
    public MapSession findById(String id) {
        if (id == null) {
            return null;
        }
        final GetItemResponse response = client.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(HTTP_SESSION_PREFIX + id)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return null;
        }
        final Map<String, AttributeValue> item = response.item();

        final MapSession session = new MapSession(id);
        session.setCreationTime(Instant.ofEpochMilli(readLong(item, A_CREATION_TIME, session.getCreationTime().toEpochMilli())));
        session.setLastAccessedTime(Instant.ofEpochMilli(readLong(item, A_LAST_ACCESSED, session.getLastAccessedTime().toEpochMilli())));
        session.setMaxInactiveInterval(Duration.ofSeconds(
                readLong(item, A_MAX_INACTIVE, MapSession.DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS)));

        final AttributeValue attributes = item.get(A_ATTRIBUTES);
        if (attributes != null && attributes.b() != null) {
            final Map<String, Object> map = deserialize(attributes.b().asByteArray());
            if (map == null) {
                // Undeserializable blob (e.g. after an incompatible class change): drop it and force re-login.
                deleteById(id);
                return null;
            }
            map.forEach(session::setAttribute);
        }

        if (session.isExpired()) {
            deleteById(id);
            return null;
        }
        return session;
    }

    @Override
    public void deleteById(String id) {
        if (id == null) {
            return;
        }
        client.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(Map.of(PK, s(HTTP_SESSION_PREFIX + id)))
                .build());
    }

    // ---- serialization helpers ------------------------------------------

    private static byte[] serialize(Map<String, Object> attributes) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new HashMap<>(attributes));
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize HTTP session attributes", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> deserialize(byte[] data) {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (Map<String, Object>) in.readObject();
        } catch (IOException | ClassNotFoundException | ClassCastException ex) {
            log.warn("Discarding unreadable HTTP session attributes ({}): {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    // ---- attribute helpers ----------------------------------------------

    private static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    private static long readLong(Map<String, AttributeValue> item, String key, long defaultValue) {
        final AttributeValue value = item.get(key);
        if (value == null || value.n() == null) {
            return defaultValue;
        }
        return Long.parseLong(value.n());
    }
}
