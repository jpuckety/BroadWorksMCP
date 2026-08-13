package co.pitayagroup.mcp.broadworks.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pluggable storage backend configuration.
 *
 * <p>{@code backend=DYNAMODB} selects the durable DynamoDB stores (with customer-managed KMS
 * encryption for secret fields); {@code backend=IN_MEMORY} selects the non-durable in-memory
 * fallback used for local / stdio / test runs.</p>
 *
 * @param backend          selected storage backend.
 * @param sessionTable     DynamoDB table holding issued opaque-token sessions, registered clients
 *                         and SAS authorizations.
 * @param httpSessionTable DynamoDB table holding the interactive Google-login HTTP sessions. These
 *                         are kept apart from {@code sessionTable}: they have their own lifecycle
 *                         (minutes, rotated on login), their own id space (servlet session ids) and
 *                         an opaque serialized payload, so mixing them into the OAuth table only
 *                         invited schema drift.
 * @param userConfigTable  DynamoDB table holding per-user Alpaca resources.
 * @param kmsKeyId          customer-managed KMS key id/ARN used to encrypt secret fields.
 * @param region            AWS region for DynamoDB / KMS clients (falls back to the SDK default chain).
 * @param allowInMemory     explicit acknowledgement that the unencrypted, non-durable in-memory
 *                          backend may be used outside a dev/local/stdio/test run.
 */
@ConfigurationProperties(prefix = "broadworks.storage")
public record StorageProperties(
        Backend backend,
        String sessionTable,
        String httpSessionTable,
        String userConfigTable,
        String kmsKeyId,
        String region,
        boolean allowInMemory
) {
    /** Available storage backends. */
    public enum Backend {
        /** Durable DynamoDB stores (production default). */
        DYNAMODB,
        /** Non-durable in-memory maps (local / stdio / tests). */
        IN_MEMORY
    }

    public StorageProperties {
        if (backend == null) {
            backend = Backend.IN_MEMORY;
        }
        if (sessionTable == null || sessionTable.isBlank()) {
            sessionTable = "broadworks-mcp-sessions";
        }
        if (httpSessionTable == null || httpSessionTable.isBlank()) {
            httpSessionTable = "broadworks-mcp-http-sessions";
        }
        if (userConfigTable == null || userConfigTable.isBlank()) {
            userConfigTable = "broadworks-mcp-user-config";
        }
    }
}
