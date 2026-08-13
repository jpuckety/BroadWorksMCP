package co.pitayagroup.mcp.broadworks.auth.session;

/**
 * The authenticated principal for a request: the canonical IdP {@code subject} and the user's email.
 *
 * <p>All per-tenant state is keyed by {@link #subject()} (never {@link #email()}).</p>
 *
 * @param subject the IdP {@code sub} claim.
 * @param email   the user's email (informational).
 */
public record UserInfo(String subject, String email) {

    /** Attribute key under which the subject is exposed on the authenticated principal. */
    public static final String SUBJECT_ATTRIBUTE = "sub";
    /** Attribute key under which the email is exposed on the authenticated principal. */
    public static final String EMAIL_ATTRIBUTE = "email";
}
