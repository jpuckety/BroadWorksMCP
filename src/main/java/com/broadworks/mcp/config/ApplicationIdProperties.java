package com.broadworks.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Logical application identifier used as the partition key for the per-user resource store.
 *
 * <p>Allows a single DynamoDB table to be shared across multiple deployments/applications while
 * keeping their data partitioned.</p>
 *
 * @param applicationId opaque application identifier.
 */
@ConfigurationProperties(prefix = "broadworks.application")
public record ApplicationIdProperties(
        String applicationId
) {
    /** Default application identifier. */
    public static final String DEFAULT_APPLICATION_ID = "broadworks-mcp";

    public ApplicationIdProperties {
        if (applicationId == null || applicationId.isBlank()) {
            applicationId = DEFAULT_APPLICATION_ID;
        }
    }
}
