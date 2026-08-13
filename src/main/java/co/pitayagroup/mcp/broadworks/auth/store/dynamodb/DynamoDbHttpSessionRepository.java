package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.CREATED_AT;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.LAST_ACCESSED_AT;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.PK;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.TYPE;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.instant;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.n;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.putInstant;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.putTtl;
import static co.pitayagroup.mcp.broadworks.auth.store.dynamodb.DynamoDbItems.s;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.store.SerializationFilters;

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
 * <p>Uses its own table (see {@code broadworks.storage.http-session-table}) rather than sharing the
 * sessions table with {@link DynamoDbSessionStore} / {@link DynamoDbAuthorizationStore}: the
 * container-managed login session has a different lifecycle (minutes, rotated on login), a different
 * shape (an opaque serialized attribute map) and a different id space (the servlet session id) from
 * the issued opaque-token sessions and registered clients, so the item id needs no prefix and the
 * two schemas cannot drift into each other. Because the session lives in DynamoDB rather than a
 * single task's memory, any load-balanced instance can serve any request in the Google sign-in /
 * authorization-code handshake, which removes the need for ALB session stickiness and lets the login
 * session survive task restarts and redeploys.</p>
 *
 * <p>Timestamps use the same attribute names and ISO-8601 format as every other item this
 * application writes ({@link DynamoDbItems#CREATED_AT} / {@link DynamoDbItems#LAST_ACCESSED_AT});
 * only {@code ttl} is numeric, as DynamoDB's native expiry requires. The session attribute map
 * (which carries the Spring Security {@code SecurityContext}, the transient OAuth2 authorization
 * request, and the saved request) is JDK-serialized into a single binary attribute. On load a fresh
 * {@link MapSession} is reconstructed for the id (so its id generator is initialised and
 * {@link MapSession#changeSessionId()} used by session-fixation protection works). An item that
 * cannot be read back (e.g. an incompatible class change, or the pre-split epoch-millis attributes)
 * is treated as an absent session so the user simply re-authenticates.</p>
 */
public class DynamoDbHttpSessionRepository implements SessionRepository<MapSession> {

    private static final Logger log = LoggerFactory.getLogger(DynamoDbHttpSessionRepository.class);

    static final String TYPE_VALUE = "http-session";

    private static final String A_MAX_INACTIVE = "maxInactiveSeconds";
    private static final String A_ATTRIBUTES = "attributes";

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
        item.put(PK, s(session.getId()));
        item.put(TYPE, s(TYPE_VALUE));
        putInstant(item, CREATED_AT, session.getCreationTime());
        putInstant(item, LAST_ACCESSED_AT, session.getLastAccessedTime());
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
            putTtl(item, session.getLastAccessedTime().plusSeconds(maxInactiveSeconds));
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
                .key(Map.of(PK, s(id)))
                .build());
        if (!response.hasItem() || response.item().isEmpty()) {
            return null;
        }
        final Map<String, AttributeValue> item = response.item();

        final MapSession session = new MapSession(id);
        final Instant createdAt = instant(item, CREATED_AT);
        final Instant lastAccessedAt = instant(item, LAST_ACCESSED_AT);
        if (createdAt == null || lastAccessedAt == null) {
            // Unusable metadata (e.g. an item written before the attributes were unified): drop it
            // and force re-login rather than resurrecting a session with invented timestamps.
            log.warn("Discarding HTTP session {} without readable {}/{} attributes",
                    id, CREATED_AT, LAST_ACCESSED_AT);
            deleteById(id);
            return null;
        }
        session.setCreationTime(createdAt);
        session.setLastAccessedTime(lastAccessedAt);
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
                .key(Map.of(PK, s(id)))
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
            in.setObjectInputFilter(SerializationFilters.HTTP_SESSION_FILTER);
            return (Map<String, Object>) in.readObject();
        } catch (IOException | ClassNotFoundException | RuntimeException ex) {
            log.warn("Discarding unreadable HTTP session attributes ({}): {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }

    // ---- attribute helpers ----------------------------------------------

    private static long readLong(Map<String, AttributeValue> item, String key, long defaultValue) {
        final AttributeValue value = item.get(key);
        if (value == null || value.n() == null) {
            return defaultValue;
        }
        return Long.parseLong(value.n());
    }
}
