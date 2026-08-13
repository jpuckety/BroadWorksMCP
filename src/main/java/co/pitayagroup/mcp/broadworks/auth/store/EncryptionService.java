package co.pitayagroup.mcp.broadworks.auth.store;

import java.util.Map;

/**
 * Abstraction over at-rest encryption of secret values (e.g. BroadWorks passwords, OIDC id tokens,
 * serialized OAuth authorizations).
 *
 * <p>Implementations return an opaque, self-describing ciphertext from {@link #encrypt} /
 * {@link #encryptBytes} that the matching {@code decrypt} call can reverse. Keeping this behind an
 * interface lets the storage layer stay agnostic of the concrete mechanism (customer-managed KMS in
 * production, no-op for local/tests).</p>
 *
 * <p>Every call takes an <em>encryption context</em>: a set of non-secret key/value pairs that is
 * cryptographically bound to the ciphertext (AWS KMS AAD). Decryption only succeeds when the exact
 * same context is supplied, which prevents a ciphertext from being relocated between records,
 * users or applications and still decrypting.</p>
 */
public interface EncryptionService {

    /**
     * @param plaintext the value to protect (may be {@code null}).
     * @param context   the encryption context to bind; must be reproduced verbatim on decrypt.
     * @return the ciphertext, or {@code null} if {@code plaintext} was {@code null}.
     */
    String encrypt(String plaintext, Map<String, String> context);

    /**
     * @param ciphertext a value previously produced by {@link #encrypt} (may be {@code null}).
     * @param context    the same context used at encryption time.
     * @return the recovered plaintext, or {@code null} if {@code ciphertext} was {@code null}.
     */
    String decrypt(String ciphertext, Map<String, String> context);

    /**
     * Encrypts an arbitrarily sized binary payload (not bounded by the KMS direct-encrypt limit).
     *
     * @param plaintext the payload to protect (may be {@code null}).
     * @param context   the encryption context to bind; must be reproduced verbatim on decrypt.
     * @return the ciphertext, or {@code null} if {@code plaintext} was {@code null}.
     */
    byte[] encryptBytes(byte[] plaintext, Map<String, String> context);

    /**
     * @param ciphertext a payload previously produced by {@link #encryptBytes} (may be {@code null}).
     * @param context    the same context used at encryption time.
     * @return the recovered payload, or {@code null} if {@code ciphertext} was {@code null}.
     */
    byte[] decryptBytes(byte[] ciphertext, Map<String, String> context);
}
