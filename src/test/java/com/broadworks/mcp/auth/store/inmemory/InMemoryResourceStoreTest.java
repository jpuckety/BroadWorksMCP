package com.broadworks.mcp.auth.store.inmemory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.broadworks.mcp.auth.store.AlpacaResource;
import com.broadworks.mcp.auth.store.EncryptionService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InMemoryResourceStoreTest {

    /** Reversible fake encryption used to prove the store persists ciphertext, not plaintext. */
    private static final class ReversibleEncryption implements EncryptionService {
        static final String PREFIX = "enc:";

        @Override
        public String encrypt(String plaintext) {
            return plaintext == null ? null : PREFIX + plaintext;
        }

        @Override
        public String decrypt(String ciphertext) {
            return ciphertext == null ? null : ciphertext.substring(PREFIX.length());
        }
    }

    private ReversibleEncryption encryption;
    private InMemoryResourceStore store;

    @BeforeEach
    void setUp() {
        encryption = new ReversibleEncryption();
        store = new InMemoryResourceStore(encryption);
    }

    private AlpacaResource resource(String id, String password) {
        return new AlpacaResource(id, "Display " + id, "as.example.com", 2208,
                "SYSTEM", "admin", password, false);
    }

    @Test
    void putGetRoundTripReturnsPlaintext() {
        store.put("sub-a", resource("res-1", "s3cret"));

        final AlpacaResource loaded = store.get("sub-a", "res-1").orElseThrow();
        assertThat(loaded.password()).isEqualTo("s3cret");
        assertThat(loaded.hostname()).isEqualTo("as.example.com");
        assertThat(loaded.port()).isEqualTo(2208);
    }

    @Test
    void listForUserReturnsOnlyThatUsersResources() {
        store.put("sub-a", resource("res-1", "pw-a1"));
        store.put("sub-a", resource("res-2", "pw-a2"));
        store.put("sub-b", resource("res-1", "pw-b1"));

        assertThat(store.listForUser("sub-a")).extracting(AlpacaResource::resourceId)
                .containsExactlyInAnyOrder("res-1", "res-2");
        assertThat(store.listForUser("sub-b")).extracting(AlpacaResource::resourceId)
                .containsExactly("res-1");
        assertThat(store.listForUser("unknown")).isEmpty();
    }

    @Test
    void perSubjectIsolationForGet() {
        store.put("sub-a", resource("res-1", "pw-a1"));

        assertThat(store.get("sub-b", "res-1")).isEmpty();
        assertThat(store.get("sub-a", "res-1")).isPresent();
    }

    @Test
    void deleteRemovesOnlyTargetResource() {
        store.put("sub-a", resource("res-1", "pw-1"));
        store.put("sub-a", resource("res-2", "pw-2"));

        store.delete("sub-a", "res-1");

        assertThat(store.get("sub-a", "res-1")).isEmpty();
        assertThat(store.get("sub-a", "res-2")).isPresent();
    }

    @Test
    void secretIsEncryptedAtRest() {
        // Encrypt through the same service and confirm the ciphertext differs from the plaintext,
        // proving the store never keeps the raw secret.
        final String plaintext = "top-secret";
        store.put("sub-a", resource("res-1", plaintext));

        final String ciphertext = encryption.encrypt(plaintext);
        assertThat(ciphertext).isNotEqualTo(plaintext).startsWith(ReversibleEncryption.PREFIX);
        // And the store still returns the decrypted value to callers.
        assertThat(store.get("sub-a", "res-1").orElseThrow().password()).isEqualTo(plaintext);
    }

    @Test
    void listReturnsEmptyForNullSubject() {
        assertThat(store.listForUser(null)).isEqualTo(List.of());
    }
}
