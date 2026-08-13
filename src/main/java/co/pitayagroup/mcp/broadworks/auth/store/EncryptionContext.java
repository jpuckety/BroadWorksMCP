package co.pitayagroup.mcp.broadworks.auth.store;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Factory for the {@link EncryptionService} encryption contexts used by the storage layer.
 *
 * <p>Each context names the purpose of the ciphertext plus the record it belongs to, so a blob
 * cannot be moved between applications, users or records and still decrypt.</p>
 */
public final class EncryptionContext {

    private static final String PURPOSE = "purpose";
    private static final String APPLICATION_ID = "applicationId";
    private static final String SUBJECT = "subject";
    private static final String RESOURCE_ID = "resourceId";
    private static final String AUTHORIZATION_ID = "authorizationId";

    private EncryptionContext() {
    }

    /** Context for a stored BroadWorks connection secret. */
    public static Map<String, String> forResource(String applicationId, String subject, String resourceId) {
        final Map<String, String> context = new LinkedHashMap<>();
        context.put(PURPOSE, "alpaca-resource");
        context.put(APPLICATION_ID, nullSafe(applicationId));
        context.put(SUBJECT, nullSafe(subject));
        context.put(RESOURCE_ID, nullSafe(resourceId));
        return context;
    }

    /** Context for tokens held against an issued session (upstream id token / IdP refresh token). */
    public static Map<String, String> forSession(String applicationId, String subject) {
        final Map<String, String> context = new LinkedHashMap<>();
        context.put(PURPOSE, "session-token");
        context.put(APPLICATION_ID, nullSafe(applicationId));
        context.put(SUBJECT, nullSafe(subject));
        return context;
    }

    /** Context for a serialized {@code OAuth2Authorization} payload. */
    public static Map<String, String> forAuthorization(String applicationId, String authorizationId) {
        final Map<String, String> context = new LinkedHashMap<>();
        context.put(PURPOSE, "oauth-authorization");
        context.put(APPLICATION_ID, nullSafe(applicationId));
        context.put(AUTHORIZATION_ID, nullSafe(authorizationId));
        return context;
    }

    /** Stable {@code k=v;k=v} rendering, used as AES-GCM additional authenticated data. */
    public static String canonical(Map<String, String> context) {
        final StringBuilder builder = new StringBuilder();
        new TreeMap<>(context).forEach((key, value) ->
                builder.append(key).append('=').append(value).append(';'));
        return builder.toString();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
