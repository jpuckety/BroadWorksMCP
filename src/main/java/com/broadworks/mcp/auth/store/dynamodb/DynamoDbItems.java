package com.broadworks.mcp.auth.store.dynamodb;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.services.dynamodb.model.AttributeValue;

/**
 * Attribute names and {@link AttributeValue} helpers shared by every DynamoDB item this application
 * writes.
 *
 * <p>The stores used to declare these independently, which let the same concept drift apart: the
 * HTTP login session wrote its creation timestamp as {@code creationTime} (epoch millis) while
 * sessions and registered clients wrote {@code createdAt} (ISO-8601). Everything now goes through
 * the constants and helpers below, so a timestamp has one name and one wire format regardless of
 * which store persisted it.</p>
 *
 * <p>Convention: timestamps are ISO-8601 strings ({@link Instant#toString()}) so items stay
 * human-readable in the console, except {@link #TTL}, which DynamoDB's native expiry requires to be
 * epoch <em>seconds</em> as a number.</p>
 */
final class DynamoDbItems {

    /** Partition key of the sessions, HTTP-session and authorization items. */
    static final String PK = "pk";
    /** Item-kind discriminator, e.g. {@code session} / {@code http-session} / {@code client}. */
    static final String TYPE = "type";
    /** Native DynamoDB expiry attribute (epoch seconds). */
    static final String TTL = "ttl";

    static final String CREATED_AT = "createdAt";
    static final String LAST_ACCESSED_AT = "lastAccessedAt";
    static final String EXPIRES_AT = "expiresAt";

    static final String SESSION_ID = "sessionId";
    static final String CLIENT_ID = "clientId";
    static final String AUTHORIZATION_ID = "authorizationId";

    private DynamoDbItems() {
    }

    static AttributeValue s(String value) {
        return AttributeValue.builder().s(value).build();
    }

    static AttributeValue n(long value) {
        return AttributeValue.builder().n(Long.toString(value)).build();
    }

    static AttributeValue stringList(List<String> values) {
        final List<AttributeValue> list = new ArrayList<>();
        for (String value : values) {
            list.add(s(value));
        }
        return AttributeValue.builder().l(list).build();
    }

    static void putIfPresent(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) {
            item.put(key, s(value));
        }
    }

    static void putInstant(Map<String, AttributeValue> item, String key, Instant value) {
        if (value != null) {
            item.put(key, s(value.toString()));
        }
    }

    /** Writes the native expiry attribute, which is epoch seconds rather than an ISO-8601 string. */
    static void putTtl(Map<String, AttributeValue> item, Instant expiry) {
        if (expiry != null) {
            item.put(TTL, n(expiry.getEpochSecond()));
        }
    }

    static String str(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        return value == null ? null : value.s();
    }

    /** Reads an ISO-8601 timestamp, tolerating an absent or unparseable value as {@code null}. */
    static Instant instant(Map<String, AttributeValue> item, String key) {
        final String raw = str(item, key);
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    static List<String> strList(Map<String, AttributeValue> item, String key) {
        final AttributeValue value = item.get(key);
        if (value == null || value.l() == null) {
            return List.of();
        }
        return value.l().stream().map(AttributeValue::s).toList();
    }
}
