package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

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
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbEnhancedClient;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import software.amazon.awssdk.enhanced.dynamodb.TableSchema;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbPartitionKey;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;

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
 * <p>Persistence goes through the DynamoDB Enhanced Client: items are mapped to and from the
 * {@link HttpSessionItem} bean via a {@link TableSchema} rather than hand-built attribute maps. The
 * enhanced client is layered over the injected low-level {@link DynamoDbClient}, so a single
 * DynamoDB client bean still backs every store.</p>
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

    private final DynamoDbTable<HttpSessionItem> table;
    private final Duration defaultMaxInactiveInterval;

    public DynamoDbHttpSessionRepository(DynamoDbClient client, String tableName,
                                         Duration defaultMaxInactiveInterval) {
        this(DynamoDbEnhancedClient.builder().dynamoDbClient(client).build(), tableName,
                defaultMaxInactiveInterval);
    }

    public DynamoDbHttpSessionRepository(DynamoDbEnhancedClient enhancedClient, String tableName,
                                         Duration defaultMaxInactiveInterval) {
        this.table = enhancedClient.table(tableName, TableSchema.fromBean(HttpSessionItem.class));
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

        final HttpSessionItem item = new HttpSessionItem();
        item.setId(session.getId());
        item.setType(TYPE_VALUE);
        item.setCreatedAt(session.getCreationTime().toString());
        item.setLastAccessedAt(session.getLastAccessedTime().toString());
        final long maxInactiveSeconds = session.getMaxInactiveInterval().getSeconds();
        item.setMaxInactiveSeconds(maxInactiveSeconds);

        final Map<String, Object> attributes = new HashMap<>();
        for (String name : session.getAttributeNames()) {
            attributes.put(name, session.getAttribute(name));
        }
        if (!attributes.isEmpty()) {
            item.setAttributes(SdkBytes.fromByteArray(serialize(attributes)));
        }

        // Native DynamoDB TTL cleanup: expire at last-access + inactivity window (skip when infinite).
        if (maxInactiveSeconds > 0) {
            item.setTtl(session.getLastAccessedTime().plusSeconds(maxInactiveSeconds).getEpochSecond());
        }

        // putItem always ignores null top-level attributes, so an empty session / infinite TTL simply
        // omits the corresponding attribute rather than writing a NULL.
        table.putItem(item);
    }

    @Override
    public MapSession findById(String id) {
        if (id == null) {
            return null;
        }
        final HttpSessionItem item = table.getItem(Key.builder().partitionValue(id).build());
        if (item == null) {
            return null;
        }

        final MapSession session = new MapSession(id);
        final Instant createdAt = parseInstant(item.getCreatedAt());
        final Instant lastAccessedAt = parseInstant(item.getLastAccessedAt());
        if (createdAt == null || lastAccessedAt == null) {
            // Unusable metadata (e.g. an item written before the attributes were unified): drop it
            // and force re-login rather than resurrecting a session with invented timestamps.
            log.warn("Discarding HTTP session {} without readable {}/{} attributes",
                    id, DynamoDbItems.CREATED_AT, DynamoDbItems.LAST_ACCESSED_AT);
            deleteById(id);
            return null;
        }
        session.setCreationTime(createdAt);
        session.setLastAccessedTime(lastAccessedAt);
        session.setMaxInactiveInterval(Duration.ofSeconds(item.getMaxInactiveSeconds() == null
                ? MapSession.DEFAULT_MAX_INACTIVE_INTERVAL_SECONDS
                : item.getMaxInactiveSeconds()));

        final SdkBytes attributes = item.getAttributes();
        if (attributes != null) {
            final Map<String, Object> map = deserialize(attributes.asByteArray());
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
        table.deleteItem(Key.builder().partitionValue(id).build());
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

    /** Reads an ISO-8601 timestamp, tolerating an absent or unparseable value as {@code null}. */
    private static Instant parseInstant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /**
     * Enhanced-client bean mapping the HTTP-session item. Timestamps are ISO-8601 strings (shared
     * with every other store via {@link DynamoDbItems}); {@link #getTtl() ttl} is epoch seconds as
     * DynamoDB's native expiry requires; the session attribute map is a JDK-serialized binary blob.
     */
    @DynamoDbBean
    public static class HttpSessionItem {

        private String id;
        private String type;
        private String createdAt;
        private String lastAccessedAt;
        private Long maxInactiveSeconds;
        private SdkBytes attributes;
        private Long ttl;

        @DynamoDbPartitionKey
        @DynamoDbAttribute(DynamoDbItems.PK)
        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        @DynamoDbAttribute(DynamoDbItems.TYPE)
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        @DynamoDbAttribute(DynamoDbItems.CREATED_AT)
        public String getCreatedAt() {
            return createdAt;
        }

        public void setCreatedAt(String createdAt) {
            this.createdAt = createdAt;
        }

        @DynamoDbAttribute(DynamoDbItems.LAST_ACCESSED_AT)
        public String getLastAccessedAt() {
            return lastAccessedAt;
        }

        public void setLastAccessedAt(String lastAccessedAt) {
            this.lastAccessedAt = lastAccessedAt;
        }

        @DynamoDbAttribute(A_MAX_INACTIVE)
        public Long getMaxInactiveSeconds() {
            return maxInactiveSeconds;
        }

        public void setMaxInactiveSeconds(Long maxInactiveSeconds) {
            this.maxInactiveSeconds = maxInactiveSeconds;
        }

        @DynamoDbAttribute(A_ATTRIBUTES)
        public SdkBytes getAttributes() {
            return attributes;
        }

        public void setAttributes(SdkBytes attributes) {
            this.attributes = attributes;
        }

        @DynamoDbAttribute(DynamoDbItems.TTL)
        public Long getTtl() {
            return ttl;
        }

        public void setTtl(Long ttl) {
            this.ttl = ttl;
        }
    }
}
