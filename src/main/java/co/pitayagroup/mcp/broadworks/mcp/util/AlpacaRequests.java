package co.pitayagroup.mcp.broadworks.mcp.util;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.generated.enums.SearchMode;
import co.ecg.alpaca.toolkit.messaging.response.Response;
import co.ecg.alpaca.toolkit.model.BroadWorksServer;

/**
 * Shared helpers for issuing and validating BroadWorks/Alpaca OCI requests, decoupled from any single
 * tool bean so every tool set can reuse them.
 */
public final class AlpacaRequests {

    private AlpacaRequests() {
    }

    /**
     * Throws an {@link AlpacaException} describing the failed {@code action} when the given response is
     * an error response.
     *
     * <p>The message includes the numeric error code <em>and</em> the BroadWorks-supplied summary and
     * detail text. The toolkit derives the numeric code by scanning the summary text (e.g.
     * {@code "[Error 4015] State/Province is not valid ..."}), so that summary is where the actionable
     * reason for the failure lives — reporting only the bare code hides why BroadWorks rejected the
     * request. Both texts are appended only when present and non-blank, and the detail is omitted when
     * it merely repeats the summary.</p>
     */
    public static void ensureSuccess(Response response, String action) {
        if (response.isErrorResponse()) {
            final StringBuilder message = new StringBuilder("BroadWorks failed to ").append(action)
                    .append(" (error code ").append(response.getErrorCode()).append(")");
            final String summary = trimToNull(response.getSummaryText());
            if (summary != null) {
                message.append(": ").append(summary);
            }
            final String detail = trimToNull(response.getDetailText());
            if (detail != null && !detail.equals(summary)) {
                message.append(summary != null ? " \u2014 " : ": ").append(detail);
            }
            throw new AlpacaException(message.toString());
        }
    }

    /** Returns the trimmed value, or {@code null} when the input is {@code null} or blank. */
    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        final String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * Flushes the Alpaca toolkit's JCS OCI response cache on {@code server}.
     *
     * <p>Get requests such as {@code ServiceProviderGet} are served from this cache (logged as
     * {@code RESPONSE FROM cache}) until they expire. After a write, or when a caller asks to
     * refresh, the cache must be cleared so the next get hits BroadWorks.</p>
     *
     * @return {@code true} when the cache was cleared; {@code false} when {@code server} is
     *         {@code null}, has no cache, or clearing failed.
     */
    public static boolean flushResponseCache(BroadWorksServer server) {
        if (server == null) {
            return false;
        }
        return server.clearCache();
    }

    /**
     * When {@code refresh} is {@code true}, flushes the OCI response cache so the following get/list
     * is fetched live from BroadWorks rather than served from cache.
     */
    public static void refreshIfRequested(BroadWorksServer server, Boolean refresh) {
        if (Boolean.TRUE.equals(refresh)) {
            flushResponseCache(server);
        }
    }

    /**
     * Parses a user-supplied search mode into the Alpaca {@link SearchMode} enum, defaulting to
     * {@link SearchMode#CONTAINS} when blank. Matching is case-insensitive.
     *
     * @throws AlpacaException if {@code mode} is not one of STARTSWITH, CONTAINS, or EQUALTO.
     */
    public static SearchMode searchMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return SearchMode.CONTAINS;
        }
        try {
            return SearchMode.valueOf(mode.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new AlpacaException("Invalid searchMode '" + mode
                    + "'; expected one of STARTSWITH, CONTAINS, EQUALTO");
        }
    }
}
