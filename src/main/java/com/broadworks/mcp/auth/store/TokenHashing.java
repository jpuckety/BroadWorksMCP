package com.broadworks.mcp.auth.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * One-way hashing of bearer credentials used as durable-storage lookup keys.
 *
 * <p>Opaque access / refresh tokens carry ≥256 bits of entropy from a CSPRNG, so an unsalted
 * SHA-256 is sufficient here: the pre-image space is not brute-forceable and a fast, deterministic
 * digest is required for {@code GetItem} / GSI lookups. Storing only the digest means a database
 * read (or a table export/backup) yields no replayable credentials.</p>
 */
public final class TokenHashing {

    private TokenHashing() {
    }

    /**
     * @param token the raw credential (may be {@code null}).
     * @return the URL-safe, unpadded Base64 SHA-256 digest, or {@code null} for a {@code null} input.
     */
    public static String sha256(String token) {
        if (token == null) {
            return null;
        }
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required but unavailable", ex);
        }
    }
}
