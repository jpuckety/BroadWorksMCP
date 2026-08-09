package com.broadworks.mcp.auth.store.dynamodb;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import com.broadworks.mcp.auth.store.EncryptionService;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;

/**
 * {@link EncryptionService} backed by AWS KMS using a customer-managed key.
 *
 * <p>{@link #encrypt} returns Base64-encoded ciphertext; {@link #decrypt} reverses it. The KMS key
 * id is provided by configuration. Secrets are never logged.</p>
 */
public class KmsEncryptionService implements EncryptionService {

    private final KmsClient kmsClient;
    private final String keyId;

    public KmsEncryptionService(KmsClient kmsClient, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("KMS key id is required for KmsEncryptionService");
        }
        this.kmsClient = kmsClient;
        this.keyId = keyId;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            return null;
        }
        final EncryptResponse response = kmsClient.encrypt(EncryptRequest.builder()
                .keyId(keyId)
                .plaintext(SdkBytes.fromString(plaintext, StandardCharsets.UTF_8))
                .build());
        return Base64.getEncoder().encodeToString(response.ciphertextBlob().asByteArray());
    }

    @Override
    public String decrypt(String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        final byte[] blob = Base64.getDecoder().decode(ciphertext);
        final DecryptResponse response = kmsClient.decrypt(DecryptRequest.builder()
                .keyId(keyId)
                .ciphertextBlob(SdkBytes.fromByteArray(blob))
                .build());
        return response.plaintext().asString(StandardCharsets.UTF_8);
    }
}
