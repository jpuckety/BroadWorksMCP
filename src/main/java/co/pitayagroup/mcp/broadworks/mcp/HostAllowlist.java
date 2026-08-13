package co.pitayagroup.mcp.broadworks.mcp;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Guards outbound BroadWorks OCI connection targets against SSRF.
 *
 * <p>A caller-supplied hostname is resolved with {@link InetAddress#getAllByName(String)} and refused
 * when ANY resolved address is loopback, link-local (including the {@code 169.254.169.254} cloud
 * instance-metadata endpoint), an RFC 1918 / unique-local private address, a wildcard/any-local
 * address, or multicast. Checking every answer (rather than the first) also defeats DNS rebinding
 * via round-robin records. Unresolvable hostnames are refused as well.</p>
 *
 * <p>Set {@code broadworks.security.allow-private-network-targets=true} to connect to a BroadWorks
 * server on a private LAN (local development only).</p>
 */
@Slf4j
@Component
public class HostAllowlist {

    /** Hostnames refused outright, whatever DNS answers with. */
    private static final Set<String> BLOCKED_HOSTNAMES = Set.of(
            "localhost",
            "metadata.google.internal");

    private final boolean allowPrivateNetworkTargets;
    private final HostResolver resolver;

    @Autowired
    public HostAllowlist(
            @Value("${broadworks.security.allow-private-network-targets:false}")
            boolean allowPrivateNetworkTargets) {
        this(allowPrivateNetworkTargets, InetAddress::getAllByName);
    }

    HostAllowlist(boolean allowPrivateNetworkTargets, HostResolver resolver) {
        this.allowPrivateNetworkTargets = allowPrivateNetworkTargets;
        this.resolver = resolver;
    }

    /**
     * @return {@code true} if the hostname resolves and no resolved address falls in a blocked range.
     */
    public boolean isAllowed(String hostname) {
        if (hostname == null || hostname.isBlank()) {
            return false;
        }
        if (allowPrivateNetworkTargets) {
            return true;
        }
        final String host = hostname.trim().toLowerCase(Locale.ROOT);
        if (BLOCKED_HOSTNAMES.contains(host)) {
            log.warn("Rejected connection target host={} (blocked hostname)", host);
            return false;
        }
        final InetAddress[] addresses;
        try {
            addresses = resolver.resolve(host);
        } catch (UnknownHostException ex) {
            log.warn("Rejected connection target host={} (unresolvable)", host);
            return false;
        }
        if (addresses == null || addresses.length == 0) {
            log.warn("Rejected connection target host={} (unresolvable)", host);
            return false;
        }
        for (InetAddress address : addresses) {
            if (isBlocked(address)) {
                log.warn("Rejected connection target host={} (resolves to blocked address {})",
                        host, address.getHostAddress());
                return false;
            }
        }
        return true;
    }

    /**
     * Blocked ranges, evaluated on the IPv4 form when the address is an IPv4-mapped / IPv4-compatible
     * IPv6 address so {@code ::ffff:169.254.169.254} cannot smuggle a link-local target through.
     */
    private static boolean isBlocked(InetAddress address) {
        final InetAddress candidate = unwrapIpv4(address);
        return candidate.isLoopbackAddress()
                || candidate.isLinkLocalAddress()
                || candidate.isSiteLocalAddress()
                || candidate.isAnyLocalAddress()
                || candidate.isMulticastAddress()
                || isUniqueLocalIpv6(candidate);
    }

    private static InetAddress unwrapIpv4(InetAddress address) {
        if (!(address instanceof Inet6Address ipv6)) {
            return address;
        }
        final byte[] bytes = ipv6.getAddress();
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return address;
            }
        }
        final boolean mapped = bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
        // ::a.b.c.d is only an IPv4-compatible address when the leading octet is non-zero; ::/::1 stay
        // IPv6 so their own any-local / loopback checks apply.
        final boolean compatible = bytes[10] == 0 && bytes[11] == 0 && bytes[12] != 0;
        if (!mapped && !compatible) {
            return address;
        }
        try {
            return InetAddress.getByAddress(new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]});
        } catch (UnknownHostException ex) {
            return address;
        }
    }

    /** IPv6 unique-local addresses ({@code fc00::/7}); {@code isSiteLocalAddress} only covers fec0::/10. */
    private static boolean isUniqueLocalIpv6(InetAddress address) {
        return address instanceof Inet6Address
                && (address.getAddress()[0] & 0xfe) == 0xfc;
    }

    /**
     * Name resolution seam so the blocked-range logic is unit-testable without DNS.
     */
    @FunctionalInterface
    interface HostResolver {
        InetAddress[] resolve(String hostname) throws UnknownHostException;
    }
}
