package co.pitayagroup.mcp.broadworks.web.portal.dto;

/**
 * Outcome of a connection verification returned to the web portal. A failed login is reported as an
 * unsuccessful verification ({@code success=false}) with a safe, secret-free message rather than an
 * HTTP error, so the SPA can surface it as user feedback; only malformed / SSRF-blocked requests are
 * rejected with {@code 400}.
 */
public record VerifyResponse(boolean success, String message) {

    /** @return a successful verification result with a friendly message. */
    public static VerifyResponse ok() {
        return new VerifyResponse(true, "Connection verified successfully.");
    }

    /** @return a failed verification result carrying the given secret-free message. */
    public static VerifyResponse failure(String message) {
        return new VerifyResponse(false, message);
    }
}
