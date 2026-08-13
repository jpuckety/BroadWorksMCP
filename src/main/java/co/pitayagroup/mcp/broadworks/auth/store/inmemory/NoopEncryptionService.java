package co.pitayagroup.mcp.broadworks.auth.store.inmemory;

import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.store.EncryptionService;

/**
 * No-op {@link EncryptionService} used for local / stdio / test runs.
 *
 * <p>It returns values unchanged and ignores the encryption context. This is only safe for
 * non-durable, single-node, local use; a real KMS-backed implementation must be used for durable
 * storage (see {@code broadworks.storage.backend}).</p>
 */
public class NoopEncryptionService implements EncryptionService {

    @Override
    public String encrypt(String plaintext, Map<String, String> context) {
        return plaintext;
    }

    @Override
    public String decrypt(String ciphertext, Map<String, String> context) {
        return ciphertext;
    }

    @Override
    public byte[] encryptBytes(byte[] plaintext, Map<String, String> context) {
        return plaintext;
    }

    @Override
    public byte[] decryptBytes(byte[] ciphertext, Map<String, String> context) {
        return ciphertext;
    }
}
