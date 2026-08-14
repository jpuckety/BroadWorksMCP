package co.pitayagroup.mcp.broadworks.mcp.util;

import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;

import co.ecg.alpaca.toolkit.generated.enums.SearchMode;
import co.ecg.alpaca.toolkit.messaging.response.Response;

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
     */
    public static void ensureSuccess(Response response, String action) {
        if (response.isErrorResponse()) {
            throw new AlpacaException("BroadWorks failed to " + action
                    + " (error code " + response.getErrorCode() + ")");
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
