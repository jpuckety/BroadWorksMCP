package com.broadworks.mcp.auth.store;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDK serialization helpers for {@link org.springframework.security.oauth2.server.authorization.OAuth2Authorization}
 * ({@link java.io.Serializable}).
 *
 * <p>Same class of risk as HTTP session serialization: incompatible Security class changes on
 * redeploy may drop pending authorizations (user re-auths). Acceptable for short-lived codes.</p>
 */
public final class AuthorizationSerialization {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationSerialization.class);

    private AuthorizationSerialization() {
    }

    public static byte[] serialize(Object value) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
             ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(value);
            out.flush();
            return bytes.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize OAuth authorization state", ex);
        }
    }

    public static <T> T deserialize(byte[] data, Class<T> type) {
        if (data == null || data.length == 0) {
            return null;
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            final Object value = in.readObject();
            if (value == null) {
                return null;
            }
            if (!type.isInstance(value)) {
                log.warn("Discarding OAuth authorization payload of unexpected type {}",
                        value.getClass().getName());
                return null;
            }
            return type.cast(value);
        } catch (IOException | ClassNotFoundException | ClassCastException ex) {
            log.warn("Discarding unreadable OAuth authorization payload ({}): {}",
                    ex.getClass().getSimpleName(), ex.getMessage());
            return null;
        }
    }
}
