package com.broadworks.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Pluggable storage backend configuration.
 *
 * <p>{@code backend=DYNAMODB} selects the durable DynamoDB stores (with customer-managed KMS
 * encryption for secret fields); {@code backend=IN_MEMORY} selects the non-durable in-memory
 * fallback used for local / stdio / test runs.</p>
 *
 * @param backend          selected storage backend.
 * @param sessionTable     DynamoDB table holding sessions and registered clients.
 * @param userConfigTable  DynamoDB table holding per-user Alpaca resources.
 * @param kmsKeyId          customer-managed KMS key id/ARN used to encrypt secret fields.
 * @param region            AWS region for DynamoDB / KMS clients (falls back to the SDK default chain).
 */
@ConfigurationProperties(prefix = "broadworks.storage")
public record StorageProperties(
        Backend backend,
        String sessionTable,
        String userConfigTable,
        String kmsKeyId,
        String region
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
        if (userConfigTable == null || userConfigTable.isBlank()) {
            userConfigTable = "broadworks-mcp-user-config";
        }
    }
}
