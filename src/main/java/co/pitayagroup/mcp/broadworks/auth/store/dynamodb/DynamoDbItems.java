package co.pitayagroup.mcp.broadworks.auth.store.dynamodb;

import java.time.Instant;

/**
 * Attribute names and value conventions shared by every DynamoDB item this application writes.
 *
 * <p>The stores used to declare these independently, which let the same concept drift apart: the
 * HTTP login session wrote its creation timestamp as {@code creationTime} (epoch millis) while
 * sessions and registered clients wrote {@code createdAt} (ISO-8601). Everything now goes through
 * the constants and helpers below (referenced from each store's {@code @DynamoDbBean} mapping), so a
 * timestamp has one name and one wire format regardless of which store persisted it.</p>
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

    /** Formats an instant as the shared ISO-8601 wire format, or {@code null} when absent. */
    static String format(Instant value) {
        return value == null ? null : value.toString();
    }

    /** Reads an ISO-8601 timestamp, tolerating an absent or unparseable value as {@code null}. */
    static Instant parseInstant(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return Instant.parse(raw);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** Epoch seconds for the native {@link #TTL} attribute, or {@code null} when there is no expiry. */
    static Long ttlEpochSeconds(Instant expiry) {
        return expiry == null ? null : expiry.getEpochSecond();
    }
}
