package com.broadworks.mcp.auth.store;

import java.io.ObjectInputFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared JEP-290 deserialization filters for the payloads this application reads back from durable
 * storage (OAuth authorizations and HTTP session attributes).
 *
 * <p>Without a filter, any write access to the backing table escalates to remote code execution via
 * a gadget chain. The filters below are allow-lists: only the JDK value types and the Spring
 * Security / application classes that legitimately appear in those object graphs may be resolved,
 * everything else is rejected. Resource limits additionally bound decompression-style attacks.</p>
 *
 * <p>The {@code java.net.URL} entry is not optional: Spring Security's
 * {@code OidcIdTokenDecoderFactory} converts the id token's {@code iss} claim to a {@link java.net.URL},
 * so it is part of every authenticated {@code SecurityContext} (via {@code OidcIdToken} /
 * {@code DefaultOidcUser}) and of the {@code Principal} stored on an {@code OAuth2Authorization}.
 * Rejecting it discarded the whole post-login session, which sent browsers into an endless
 * authorize -> Google -> callback -> authorize redirect loop.</p>
 *
 * <p>Because the JDK reports a filter rejection as {@code InvalidClassException: filter status:
 * REJECTED} <em>without</em> naming the class, every rejection is logged here; otherwise a single
 * missing entry is undiagnosable from the logs.</p>
 */
public final class SerializationFilters {

    private static final Logger log = LoggerFactory.getLogger(SerializationFilters.class);

    private static final String LIMITS =
            "maxdepth=64;maxrefs=100000;maxbytes=1048576;maxarray=100000;";

    private static final String ALLOWED_PACKAGES =
            "java.lang.*;"
                    + "java.lang.invoke.SerializedLambda;"
                    + "java.math.*;"
                    + "java.net.URI;"
                    + "java.net.URL;"
                    + "java.time.**;"
                    + "java.util.*;"
                    + "java.util.concurrent.ConcurrentHashMap;"
                    + "org.springframework.security.**;"
                    + "org.springframework.util.**;"
                    + "com.broadworks.mcp.**;"
                    + "!*";

    /** Filter applied to serialized {@code OAuth2Authorization} payloads. */
    public static final ObjectInputFilter AUTHORIZATION_FILTER = allowList("OAuth authorization");

    /** Filter applied to serialized HTTP session attribute maps. */
    public static final ObjectInputFilter HTTP_SESSION_FILTER = allowList("HTTP session");

    private SerializationFilters() {
    }

    /**
     * Builds the allow-list filter, wrapped so that a rejection identifies what was rejected.
     *
     * @param payload human-readable name of the payload the filter guards, used in the log message
     */
    private static ObjectInputFilter allowList(String payload) {
        final ObjectInputFilter delegate = ObjectInputFilter.Config.createFilter(LIMITS + ALLOWED_PACKAGES);
        return info -> {
            final ObjectInputFilter.Status status = delegate.checkInput(info);
            if (status == ObjectInputFilter.Status.REJECTED) {
                log.warn("Deserialization filter rejected {} payload: class={} depth={} references={} "
                                + "streamBytes={} arrayLength={}",
                        payload,
                        info.serialClass() == null ? "<none, resource limit exceeded>" : info.serialClass().getName(),
                        info.depth(), info.references(), info.streamBytes(), info.arrayLength());
            }
            return status;
        };
    }
}
