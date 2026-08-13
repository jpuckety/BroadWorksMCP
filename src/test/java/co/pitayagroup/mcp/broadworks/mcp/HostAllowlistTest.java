package co.pitayagroup.mcp.broadworks.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the SSRF guard. DNS is stubbed via the package-private resolver seam so the blocked
 * ranges (and the DNS-rebinding round-robin case) are exercised without touching the network.
 */
class HostAllowlistTest {

    private static HostAllowlist blocking(String... resolvedAddresses) {
        return new HostAllowlist(false, hostname -> toAddresses(hostname, resolvedAddresses));
    }

    private static InetAddress[] toAddresses(String hostname, String... addresses) {
        final InetAddress[] resolved = new InetAddress[addresses.length];
        for (int i = 0; i < addresses.length; i++) {
            try {
                resolved[i] = InetAddress.getByName(addresses[i]);
            } catch (UnknownHostException ex) {
                throw new IllegalStateException("literal address expected: " + addresses[i], ex);
            }
        }
        return resolved;
    }

    @Test
    void publicAddressIsAllowed() {
        assertThat(blocking("93.184.216.34").isAllowed("portal.example.com")).isTrue();
        assertThat(blocking("2606:2800:220:1:248:1893:25c8:1946").isAllowed("portal.example.com"))
                .isTrue();
    }

    @Test
    void instanceMetadataEndpointIsBlocked() {
        assertThat(blocking("169.254.169.254").isAllowed("169.254.169.254")).isFalse();
        assertThat(blocking("169.254.169.254").isAllowed("imds.attacker.tld")).isFalse();
        // IPv4-mapped IPv6 form of the same address.
        assertThat(blocking("::ffff:169.254.169.254").isAllowed("imds.attacker.tld")).isFalse();
    }

    @Test
    void loopbackLinkLocalPrivateAnyLocalAndMulticastAreBlocked() {
        assertThat(blocking("127.0.0.1").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("127.9.9.9").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("::1").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("fe80::1").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("10.0.0.5").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("172.16.3.4").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("192.168.1.1").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("fd00::1").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("0.0.0.0").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("::").isAllowed("host.attacker.tld")).isFalse();
        assertThat(blocking("224.0.0.1").isAllowed("host.attacker.tld")).isFalse();
    }

    @Test
    void blockedLiteralHostnamesAreRejectedWithoutResolution() {
        final HostAllowlist allowlist = new HostAllowlist(false, hostname -> {
            throw new AssertionError("must not resolve " + hostname);
        });
        assertThat(allowlist.isAllowed("localhost")).isFalse();
        assertThat(allowlist.isAllowed("LOCALHOST")).isFalse();
        assertThat(allowlist.isAllowed("metadata.google.internal")).isFalse();
    }

    @Test
    void anyBlockedAddressInARoundRobinAnswerRejectsTheHost() {
        assertThat(blocking("93.184.216.34", "169.254.169.254").isAllowed("rebind.attacker.tld"))
                .isFalse();
        assertThat(blocking("93.184.216.34", "93.184.216.35").isAllowed("portal.example.com"))
                .isTrue();
    }

    @Test
    void unresolvableAndBlankHostnamesAreRejected() {
        final HostAllowlist allowlist = new HostAllowlist(false, hostname -> {
            throw new UnknownHostException(hostname);
        });
        assertThat(allowlist.isAllowed("nope.invalid")).isFalse();
        assertThat(allowlist.isAllowed("  ")).isFalse();
        assertThat(allowlist.isAllowed(null)).isFalse();
        assertThat(new HostAllowlist(false, hostname -> new InetAddress[0]).isAllowed("empty.invalid"))
                .isFalse();
    }

    @Test
    void optingInAllowsPrivateNetworkTargets() {
        final HostAllowlist allowlist = new HostAllowlist(true, hostname -> {
            throw new AssertionError("must not resolve " + hostname);
        });
        assertThat(allowlist.isAllowed("192.168.1.10")).isTrue();
        assertThat(allowlist.isAllowed("localhost")).isTrue();
        assertThat(allowlist.isAllowed("  ")).isFalse();
    }
}
