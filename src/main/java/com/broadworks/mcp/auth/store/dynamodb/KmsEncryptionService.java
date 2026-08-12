package com.broadworks.mcp.auth.store.dynamodb;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.broadworks.mcp.auth.store.EncryptionContext;
import com.broadworks.mcp.auth.store.EncryptionService;

import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.DecryptResponse;
import software.amazon.awssdk.services.kms.model.EncryptRequest;
import software.amazon.awssdk.services.kms.model.EncryptResponse;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyResponse;

/**
 * {@link EncryptionService} backed by AWS KMS using a customer-managed key.
 *
 * <p>{@link #encrypt} calls KMS directly (values are well under the 4 KiB direct-encrypt limit) and
 * returns Base64-encoded ciphertext. {@link #encryptBytes} uses envelope encryption — a per-payload
 * AES-256-GCM data key wrapped by the CMK — so arbitrarily large payloads are supported.</p>
 *
 * <p>The caller-supplied encryption context is passed to KMS on both encrypt and decrypt, binding
 * the ciphertext to the record it belongs to. Secrets are never logged.</p>
 */
public class KmsEncryptionService implements EncryptionService {

    private static final byte ENVELOPE_VERSION = 1;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private final KmsClient kmsClient;
    private final String keyId;
    private final SecureRandom secureRandom = new SecureRandom();

    public KmsEncryptionService(KmsClient kmsClient, String keyId) {
        if (keyId == null || keyId.isBlank()) {
            throw new IllegalArgumentException("KMS key id is required for KmsEncryptionService");
        }
        this.kmsClient = kmsClient;
        this.keyId = keyId;
    }

    @Override
    public String encrypt(String plaintext, Map<String, String> context) {
        if (plaintext == null) {
            return null;
        }
        final EncryptResponse response = kmsClient.encrypt(EncryptRequest.builder()
                .keyId(keyId)
                .encryptionContext(requireContext(context))
                .plaintext(SdkBytes.fromString(plaintext, StandardCharsets.UTF_8))
                .build());
        return Base64.getEncoder().encodeToString(response.ciphertextBlob().asByteArray());
    }

    @Override
    public String decrypt(String ciphertext, Map<String, String> context) {
        if (ciphertext == null) {
            return null;
        }
        final byte[] blob = Base64.getDecoder().decode(ciphertext);
        final DecryptResponse response = kmsClient.decrypt(DecryptRequest.builder()
                .keyId(keyId)
                .encryptionContext(requireContext(context))
                .ciphertextBlob(SdkBytes.fromByteArray(blob))
                .build());
        return response.plaintext().asString(StandardCharsets.UTF_8);
    }

    @Override
    public byte[] encryptBytes(byte[] plaintext, Map<String, String> context) {
        if (plaintext == null) {
            return null;
        }
        final Map<String, String> encryptionContext = requireContext(context);
        final GenerateDataKeyResponse dataKey = kmsClient.generateDataKey(GenerateDataKeyRequest.builder()
                .keyId(keyId)
                .keySpec(DataKeySpec.AES_256)
                .encryptionContext(encryptionContext)
                .build());
        final byte[] wrappedKey = dataKey.ciphertextBlob().asByteArray();
        final byte[] rawKey = dataKey.plaintext().asByteArray();
        final byte[] iv = new byte[GCM_IV_BYTES];
        secureRandom.nextBytes(iv);
        try {
            final byte[] sealed = cipher(Cipher.ENCRYPT_MODE, rawKey, iv, encryptionContext)
                    .doFinal(plaintext);
            return ByteBuffer.allocate(1 + Integer.BYTES + wrappedKey.length + GCM_IV_BYTES + sealed.length)
                    .put(ENVELOPE_VERSION)
                    .putInt(wrappedKey.length)
                    .put(wrappedKey)
                    .put(iv)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to encrypt payload", ex);
        } finally {
            java.util.Arrays.fill(rawKey, (byte) 0);
        }
    }

    @Override
    public byte[] decryptBytes(byte[] ciphertext, Map<String, String> context) {
        if (ciphertext == null) {
            return null;
        }
        final Map<String, String> encryptionContext = requireContext(context);
        final ByteBuffer buffer = ByteBuffer.wrap(ciphertext);
        if (buffer.remaining() < 1 + Integer.BYTES || buffer.get() != ENVELOPE_VERSION) {
            throw new IllegalStateException("Unrecognised envelope ciphertext");
        }
        final int wrappedKeyLength = buffer.getInt();
        if (wrappedKeyLength <= 0 || buffer.remaining() < wrappedKeyLength + GCM_IV_BYTES) {
            throw new IllegalStateException("Malformed envelope ciphertext");
        }
        final byte[] wrappedKey = new byte[wrappedKeyLength];
        buffer.get(wrappedKey);
        final byte[] iv = new byte[GCM_IV_BYTES];
        buffer.get(iv);
        final byte[] sealed = new byte[buffer.remaining()];
        buffer.get(sealed);

        final DecryptResponse unwrapped = kmsClient.decrypt(DecryptRequest.builder()
                .keyId(keyId)
                .encryptionContext(encryptionContext)
                .ciphertextBlob(SdkBytes.fromByteArray(wrappedKey))
                .build());
        final byte[] rawKey = unwrapped.plaintext().asByteArray();
        try {
            return cipher(Cipher.DECRYPT_MODE, rawKey, iv, encryptionContext).doFinal(sealed);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to decrypt payload", ex);
        } finally {
            java.util.Arrays.fill(rawKey, (byte) 0);
        }
    }

    private static Cipher cipher(int mode, byte[] rawKey, byte[] iv, Map<String, String> context)
            throws GeneralSecurityException {
        final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(rawKey, "AES"), new GCMParameterSpec(GCM_TAG_BITS, iv));
        cipher.updateAAD(EncryptionContext.canonical(context).getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private static Map<String, String> requireContext(Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            throw new IllegalArgumentException("An encryption context is required");
        }
        return context;
    }
}
