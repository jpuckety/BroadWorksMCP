package co.pitayagroup.mcp.broadworks.auth.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AuthorizationSerializationTest {

    @Test
    void roundTripsAllowedValueTypes() {
        final HashMap<String, Object> payload = new HashMap<>();
        payload.put("scope", "openid");
        payload.put("count", 3);

        final byte[] bytes = AuthorizationSerialization.serialize(payload);

        @SuppressWarnings("unchecked")
        final Map<String, Object> loaded = AuthorizationSerialization.deserialize(bytes, Map.class);
        assertThat(loaded).containsEntry("scope", "openid").containsEntry("count", 3);
    }

    @Test
    void rejectsClassesOutsideTheAllowList() throws IOException {
        // java.io.File is Serializable but is not on the allow-list: a tampered payload referencing
        // any such class must be discarded rather than instantiated.
        final byte[] bytes = writeRaw(new File("/tmp/attacker"));

        assertThat(AuthorizationSerialization.deserialize(bytes, File.class)).isNull();
    }

    @Test
    void rejectsDisallowedClassNestedInsideAnAllowedContainer() throws IOException {
        final HashMap<String, Object> payload = new HashMap<>();
        payload.put("gadget", new File("/tmp/attacker"));

        final byte[] bytes = writeRaw(payload);

        assertThat(AuthorizationSerialization.deserialize(bytes, Map.class)).isNull();
    }

    @Test
    void returnsNullForEmptyOrMissingPayloads() {
        assertThat(AuthorizationSerialization.deserialize(null, Map.class)).isNull();
        assertThat(AuthorizationSerialization.deserialize(new byte[0], Map.class)).isNull();
    }

    /** Writes a payload without going through the production filter, simulating a tampered item. */
    private static byte[] writeRaw(Object value) throws IOException {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
            out.flush();
            return bytes.toByteArray();
        }
    }
}
