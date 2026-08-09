package com.broadworks.mcp.auth.identity;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Deterministic test {@link IdentityProvider} that never contacts a real IdP.
 *
 * <p>Returns fixed claims for a configured {@code sub}/{@code email}. Tokens whose value contains
 * {@code "unverified"} are treated as {@code email_verified == false} and rejected, allowing tests
 * to exercise the negative path without any network.</p>
 */
public class StubIdentityProvider implements IdentityProvider {

    public static final String ISSUER = "https://stub-idp.test";
    public static final String AUDIENCE = "stub-client";

    private final String subject;
    private final String email;

    public StubIdentityProvider(String subject, String email) {
        this.subject = subject;
        this.email = email;
    }

    @Override
    public URI authCodeUrl(String state, String codeChallenge) {
        return URI.create(ISSUER + "/authorize?state=" + state + "&code_challenge=" + codeChallenge);
    }

    @Override
    public ExchangeResult exchange(String code, String codeVerifier) {
        final String idToken = "stub-id-token-for-" + code;
        final IdTokenClaims claims = verifyIdToken(idToken);
        return new ExchangeResult(claims, new RawTokens(idToken, "stub-access", "stub-refresh",
                Instant.now().plus(1, ChronoUnit.HOURS)));
    }

    @Override
    public IdTokenClaims verifyIdToken(String rawIdToken) {
        if (rawIdToken != null && rawIdToken.contains("unverified")) {
            throw new IdentityProviderException("ID token email_verified is not true");
        }
        return new IdTokenClaims(subject, email, true, ISSUER, AUDIENCE,
                Instant.now().plus(1, ChronoUnit.HOURS));
    }
}
