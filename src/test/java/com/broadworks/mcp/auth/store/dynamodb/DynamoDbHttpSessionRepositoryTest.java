package com.broadworks.mcp.auth.store.dynamodb;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.session.MapSession;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemResponse;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemResponse;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemResponse;

/**
 * Unit tests for {@link DynamoDbHttpSessionRepository} using an in-memory fake {@link DynamoDbClient}
 * (only the three item operations the repository uses are implemented). Verifies that the interactive
 * login session — including a serialized Spring Security {@code SecurityContext} — round-trips, that
 * session-id rotation removes the old item, that expired sessions are evicted, that an unreadable
 * attribute blob is treated as an absent session, and that the persisted metadata uses the shared
 * {@code createdAt} / {@code lastAccessedAt} ISO-8601 attributes rather than the old
 * {@code creationTime} / {@code lastAccessedTime} epoch-millis pair.
 */
class DynamoDbHttpSessionRepositoryTest {

    private static final String TABLE = "http-sessions";
    private static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";

    private final FakeDynamoDbClient dynamo = new FakeDynamoDbClient();
    private final DynamoDbHttpSessionRepository repository =
            new DynamoDbHttpSessionRepository(dynamo, TABLE, Duration.ofMinutes(30));

    @Test
    void savesAndReloadsSessionIncludingSecurityContext() {
        final MapSession session = repository.createSession();
        final SecurityContext context = new SecurityContextImpl(
                new UsernamePasswordAuthenticationToken("sub-123", "n/a", List.of()));
        session.setAttribute(SECURITY_CONTEXT_ATTR, context);
        session.setAttribute("count", 7);

        repository.save(session);

        final MapSession loaded = repository.findById(session.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getId()).isEqualTo(session.getId());
        assertThat(loaded.<Integer>getAttribute("count")).isEqualTo(7);
        final SecurityContext reloaded = loaded.getAttribute(SECURITY_CONTEXT_ATTR);
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getAuthentication().getPrincipal()).isEqualTo("sub-123");
    }

    @Test
    void persistsTimestampsUnderTheSharedAttributeNamesAsIso8601() {
        final MapSession session = repository.createSession();
        repository.save(session);

        // The item id is the plain session id: the dedicated table needs no key prefix.
        final Map<String, AttributeValue> item = dynamo.items.get(session.getId());
        assertThat(item).isNotNull();
        assertThat(item.get("createdAt").s()).isEqualTo(session.getCreationTime().toString());
        assertThat(item.get("lastAccessedAt").s()).isEqualTo(session.getLastAccessedTime().toString());
        // The legacy names/format must be gone, otherwise the same concept has two spellings again.
        assertThat(item).doesNotContainKeys("creationTime", "lastAccessedTime");
        // Only the native expiry attribute stays numeric (epoch seconds), as DynamoDB requires.
        assertThat(item.get("ttl").n()).isNotNull();
    }

    @Test
    void itemWithoutReadableTimestampsIsTreatedAsAbsentSession() {
        // A pre-split item: epoch-millis creationTime/lastAccessedTime and no createdAt/lastAccessedAt.
        final Map<String, AttributeValue> legacy = new HashMap<>();
        legacy.put("pk", AttributeValue.builder().s("legacy-id").build());
        legacy.put("creationTime", AttributeValue.builder()
                .n(Long.toString(Instant.now().toEpochMilli())).build());
        legacy.put("lastAccessedTime", AttributeValue.builder()
                .n(Long.toString(Instant.now().toEpochMilli())).build());
        dynamo.items.put("legacy-id", legacy);

        assertThat(repository.findById("legacy-id")).isNull();
        assertThat(dynamo.items).doesNotContainKey("legacy-id");
    }

    @Test
    void changingSessionIdRemovesTheOldItem() {
        final MapSession session = repository.createSession();
        session.setAttribute("k", "v");
        repository.save(session);
        final String originalId = session.getId();

        final String newId = session.changeSessionId();
        repository.save(session);

        assertThat(repository.findById(originalId)).isNull();
        final MapSession loaded = repository.findById(newId);
        assertThat(loaded).isNotNull();
        assertThat(loaded.<String>getAttribute("k")).isEqualTo("v");
    }

    @Test
    void expiredSessionIsEvictedAndReturnsNull() {
        final MapSession session = repository.createSession();
        session.setMaxInactiveInterval(Duration.ofMinutes(1));
        session.setLastAccessedTime(Instant.now().minus(Duration.ofMinutes(5)));
        repository.save(session);

        assertThat(repository.findById(session.getId())).isNull();
        // The evicted item must also be removed from the backing store.
        assertThat(dynamo.items).doesNotContainKey(session.getId());
    }

    @Test
    void unreadableAttributeBlobIsTreatedAsAbsentSession() {
        final MapSession session = repository.createSession();
        session.setAttribute("k", "v");
        repository.save(session);

        // Corrupt the serialized attribute blob to simulate an incompatible/garbled payload.
        final String key = session.getId();
        final Map<String, AttributeValue> item = new HashMap<>(dynamo.items.get(key));
        item.put("attributes", AttributeValue.builder()
                .b(SdkBytes.fromByteArray(new byte[] {1, 2, 3, 4})).build());
        dynamo.items.put(key, item);

        assertThat(repository.findById(session.getId())).isNull();
        assertThat(dynamo.items).doesNotContainKey(key);
    }

    @Test
    void findByIdReturnsNullWhenAbsent() {
        assertThat(repository.findById("does-not-exist")).isNull();
    }

    /**
     * Minimal in-memory {@link DynamoDbClient} implementing only the single-item operations used by
     * {@link DynamoDbHttpSessionRepository}. Keyed by the {@code pk} attribute value.
     */
    private static final class FakeDynamoDbClient implements DynamoDbClient {

        private final Map<String, Map<String, AttributeValue>> items = new HashMap<>();

        @Override
        public PutItemResponse putItem(PutItemRequest request) {
            items.put(request.item().get("pk").s(), new HashMap<>(request.item()));
            return PutItemResponse.builder().build();
        }

        @Override
        public GetItemResponse getItem(GetItemRequest request) {
            final Map<String, AttributeValue> item = items.get(request.key().get("pk").s());
            if (item == null) {
                return GetItemResponse.builder().build();
            }
            return GetItemResponse.builder().item(item).build();
        }

        @Override
        public DeleteItemResponse deleteItem(DeleteItemRequest request) {
            items.remove(request.key().get("pk").s());
            return DeleteItemResponse.builder().build();
        }

        @Override
        public String serviceName() {
            return "dynamodb-fake";
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
