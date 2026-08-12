package com.broadworks.mcp.auth.store;

import java.io.ObjectInputFilter;

/**
 * Shared JEP-290 deserialization filters for the payloads this application reads back from durable
 * storage (OAuth authorizations and HTTP session attributes).
 *
 * <p>Without a filter, any write access to the backing table escalates to remote code execution via
 * a gadget chain. The filters below are allow-lists: only the JDK value types and the Spring
 * Security / application classes that legitimately appear in those object graphs may be resolved,
 * everything else is rejected. Resource limits additionally bound decompression-style attacks.</p>
 */
public final class SerializationFilters {

    private static final String LIMITS =
            "maxdepth=64;maxrefs=100000;maxbytes=1048576;maxarray=100000;";

    private static final String ALLOWED_PACKAGES =
            "java.lang.*;"
                    + "java.lang.invoke.SerializedLambda;"
                    + "java.math.*;"
                    + "java.net.URI;"
                    + "java.time.**;"
                    + "java.util.*;"
                    + "java.util.concurrent.ConcurrentHashMap;"
                    + "org.springframework.security.**;"
                    + "org.springframework.util.**;"
                    + "com.broadworks.mcp.**;"
                    + "!*";

    /** Filter applied to serialized {@code OAuth2Authorization} payloads. */
    public static final ObjectInputFilter AUTHORIZATION_FILTER =
            ObjectInputFilter.Config.createFilter(LIMITS + ALLOWED_PACKAGES);

    /** Filter applied to serialized HTTP session attribute maps. */
    public static final ObjectInputFilter HTTP_SESSION_FILTER =
            ObjectInputFilter.Config.createFilter(LIMITS + ALLOWED_PACKAGES);

    private SerializationFilters() {
    }
}
