package co.pitayagroup.mcp.broadworks.auth.session;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Generates opaque tokens (session ids, access/refresh values) using a cryptographically strong
 * random source. Each token is at least {@value #TOKEN_BYTES} random bytes, URL-safe Base64 encoded
 * without padding.
 */
public class OpaqueTokenFactory {

    /** Minimum number of random bytes per token (blueprint requires &ge; 32). */
    public static final int TOKEN_BYTES = 32;

    private final SecureRandom secureRandom;

    public OpaqueTokenFactory() {
        this(new SecureRandom());
    }

    public OpaqueTokenFactory(SecureRandom secureRandom) {
        this.secureRandom = secureRandom;
    }

    /**
     * @return a new opaque token value.
     */
    public String create() {
        final byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
