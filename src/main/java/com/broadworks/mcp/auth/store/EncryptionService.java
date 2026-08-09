package com.broadworks.mcp.auth.store;

/**
 * Abstraction over at-rest encryption of secret values (e.g. BroadWorks passwords).
 *
 * <p>Implementations return an opaque, self-describing ciphertext string from {@link #encrypt} that
 * {@link #decrypt} can reverse. Keeping this behind an interface lets the storage layer stay
 * agnostic of the concrete mechanism (customer-managed KMS in production, no-op for local/tests).</p>
 */
public interface EncryptionService {

    /**
     * @param plaintext the value to protect (may be {@code null}).
     * @return the ciphertext, or {@code null} if {@code plaintext} was {@code null}.
     */
    String encrypt(String plaintext);

    /**
     * @param ciphertext a value previously produced by {@link #encrypt} (may be {@code null}).
     * @return the recovered plaintext, or {@code null} if {@code ciphertext} was {@code null}.
     */
    String decrypt(String ciphertext);
}
