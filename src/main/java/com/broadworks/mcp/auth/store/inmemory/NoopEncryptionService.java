package com.broadworks.mcp.auth.store.inmemory;

import com.broadworks.mcp.auth.store.EncryptionService;

/**
 * No-op {@link EncryptionService} used for local / stdio / test runs.
 *
 * <p>It returns values unchanged. This is only safe for non-durable, single-node, local use; a real
 * KMS-backed implementation must be used for durable storage.</p>
 */
public class NoopEncryptionService implements EncryptionService {

    @Override
    public String encrypt(String plaintext) {
        return plaintext;
    }

    @Override
    public String decrypt(String ciphertext) {
        return ciphertext;
    }
}
