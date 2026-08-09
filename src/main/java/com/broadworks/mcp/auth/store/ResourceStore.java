package com.broadworks.mcp.auth.store;

import java.util.List;
import java.util.Optional;

/**
 * Per-user store for BroadWorks/Alpaca connection resources.
 *
 * <p>Strict tenant isolation: every operation is scoped by {@code subject} (the IdP {@code sub}),
 * never by email. Secret fields are encrypted at rest by the implementation; callers always work
 * with plaintext {@link AlpacaResource} values.</p>
 */
public interface ResourceStore {

    /**
     * @return all resources owned by the given user (decrypted), possibly empty.
     */
    List<AlpacaResource> listForUser(String subject);

    /**
     * @return the resource with the given id owned by the user (decrypted), if present.
     */
    Optional<AlpacaResource> get(String subject, String resourceId);

    /**
     * Creates or replaces a resource for the given user (secret fields encrypted at rest).
     */
    void put(String subject, AlpacaResource resource);

    /**
     * Deletes the resource with the given id for the user. No-op if it does not exist.
     */
    void delete(String subject, String resourceId);
}
