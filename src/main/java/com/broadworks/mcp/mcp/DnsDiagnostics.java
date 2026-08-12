package com.broadworks.mcp.mcp;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Explains a name-resolution failure using only what the JVM can observe from inside its own
 * container.
 *
 * <p>The JDK surfaces the C library's verdict verbatim, so a BroadWorks login can fail with nothing
 * more than {@code java.net.UnknownHostException: portal.vwave.net: Temporary failure in name
 * resolution}. That message hides the two things needed to act on it: <em>which</em> resolver was
 * asked, and whether the whole resolver path is broken or only this one zone. In ECS/Fargate the
 * resolver is the Amazon-provided one on the task's ENI, reached without traversing the NAT gateway,
 * so a task that happily talks to DynamoDB and Google can still fail for a single external domain -
 * exactly the case this class is meant to make visible in CloudWatch without shelling into the
 * task.</p>
 *
 * <p>"Temporary failure in name resolution" ({@code EAI_AGAIN}) means the resolver did not answer or
 * answered {@code SERVFAIL}; "Name or service not known" ({@code EAI_NONAME}) means it answered that
 * the name does not exist. The report below therefore pairs the configured nameservers with a repeat
 * lookup of the failing host and a lookup of its registrable parent domain: if the parent resolves
 * and the host does not, resolution works and the record is the problem; if neither resolves while
 * other traffic is fine, the resolver cannot answer for that zone.</p>
 */
final class DnsDiagnostics {

    /** Standard glibc resolver configuration, present in every Linux container image. */
    static final Path RESOLV_CONF = Path.of("/etc/resolv.conf");

    private DnsDiagnostics() {
    }

    /**
     * Whether {@code failure} (or any of its causes) is a name-resolution failure, i.e. whether the
     * host never resolved rather than the connection being refused, timing out or being rejected.
     */
    static boolean isNameResolutionFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof UnknownHostException) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }

    /** Single-line report for {@code hostname}, safe to log (it contains no credentials). */
    static String describe(String hostname) {
        return describe(hostname, RESOLV_CONF);
    }

    static String describe(String hostname, Path resolvConf) {
        final StringJoiner report = new StringJoiner(" ");
        report.add("resolver=[" + resolverConfig(resolvConf) + "]");
        report.add("lookup(" + hostname + ")=" + lookup(hostname));
        final String parent = parentDomain(hostname);
        if (parent != null) {
            report.add("lookup(" + parent + ")=" + lookup(parent));
        }
        return report.toString();
    }

    /**
     * The {@code nameserver} / {@code search} / {@code options} lines of the resolver configuration,
     * which name the server actually queried and any search domains appended to short names.
     */
    static String resolverConfig(Path resolvConf) {
        final List<String> lines = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(resolvConf)) {
                final String trimmed = line.trim();
                if (trimmed.startsWith("nameserver") || trimmed.startsWith("search")
                        || trimmed.startsWith("options")) {
                    lines.add(trimmed);
                }
            }
        } catch (Exception ex) {
            return resolvConf + " unreadable: " + ex.getClass().getSimpleName();
        }
        return lines.isEmpty() ? "no nameserver configured in " + resolvConf : String.join("; ", lines);
    }

    /** Repeats the lookup, reporting either the addresses found or the resolver's own verdict. */
    static String lookup(String name) {
        try {
            final InetAddress[] addresses = InetAddress.getAllByName(name);
            final StringJoiner joined = new StringJoiner(",");
            for (InetAddress address : addresses) {
                joined.add(address.getHostAddress());
            }
            return "ok(" + joined + ")";
        } catch (UnknownHostException ex) {
            // The message is the C library's reason, which is the interesting part (EAI_AGAIN vs
            // EAI_NONAME); it carries no user data beyond the hostname already being logged.
            return "failed(" + ex.getMessage() + ")";
        }
    }

    /**
     * The registrable parent of {@code hostname} (its last two labels), or {@code null} when the
     * hostname is already that short or is an IP literal - in both cases there is nothing extra to
     * learn from a second lookup.
     */
    static String parentDomain(String hostname) {
        if (hostname == null) {
            return null;
        }
        final String trimmed = hostname.trim();
        if (trimmed.isEmpty() || isIpLiteral(trimmed)) {
            return null;
        }
        final String[] labels = trimmed.split("\\.");
        if (labels.length < 3) {
            return null;
        }
        return labels[labels.length - 2] + "." + labels[labels.length - 1];
    }

    private static boolean isIpLiteral(String host) {
        if (host.indexOf(':') >= 0) {
            return true;
        }
        return Character.isDigit(host.charAt(host.length() - 1))
                && host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }
}
