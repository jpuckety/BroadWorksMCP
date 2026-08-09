package com.broadworks.mcp.auth.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import com.broadworks.mcp.config.OidcProperties;
import com.broadworks.mcp.config.PublicBaseUrlProperties;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Offline verification tests for {@link GoogleIdentityProvider#verifyIdToken(String)} using real
 * RS256-signed tokens and a decoder built from a locally generated key pair.
 */
class GoogleIdentityProviderTest {

    private static final String ISSUER = "https://issuer.test";
    private static final String CLIENT_ID = "client-123";

    private static KeyPair keyPair;
    private static KeyPair otherKeyPair;
    private static GoogleIdentityProvider provider;

    @BeforeAll
    static void setUp() throws Exception {
        final KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        otherKeyPair = generator.generateKeyPair();

        final JwtDecoder decoder = NimbusJwtDecoder
                .withPublicKey((RSAPublicKey) keyPair.getPublic())
                .build();

        final OidcProperties oidc = new OidcProperties(ISSUER, CLIENT_ID, null, null);
        provider = new GoogleIdentityProvider(oidc, new PublicBaseUrlProperties(null),
                null, null, decoder);
    }

    private String signedToken(RSAPrivateKey signingKey, String issuer, String audience, String sub,
                               boolean emailVerified, Instant expiry) throws Exception {
        final JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiry))
                .issueTime(Date.from(Instant.now()))
                .claim("email", "user@example.com")
                .claim("email_verified", emailVerified);
        if (sub != null) {
            claims.subject(sub);
        }
        final SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(signingKey));
        return jwt.serialize();
    }

    @Test
    void acceptsValidToken() throws Exception {
        final String token = signedToken((RSAPrivateKey) keyPair.getPrivate(), ISSUER, CLIENT_ID,
                "user-sub-1", true, Instant.now().plus(1, ChronoUnit.HOURS));

        final IdTokenClaims claims = provider.verifyIdToken(token);

        assertThat(claims.sub()).isEqualTo("user-sub-1");
        assertThat(claims.email()).isEqualTo("user@example.com");
        assertThat(claims.emailVerified()).isTrue();
        assertThat(claims.iss()).isEqualTo(ISSUER);
        assertThat(claims.aud()).isEqualTo(CLIENT_ID);
    }

    @Test
    void rejectsEmailNotVerified() throws Exception {
        final String token = signedToken((RSAPrivateKey) keyPair.getPrivate(), ISSUER, CLIENT_ID,
                "user-sub-1", false, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> provider.verifyIdToken(token))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("email_verified");
    }

    @Test
    void rejectsWrongAudience() throws Exception {
        final String token = signedToken((RSAPrivateKey) keyPair.getPrivate(), ISSUER, "other-client",
                "user-sub-1", true, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> provider.verifyIdToken(token))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("audience");
    }

    @Test
    void rejectsWrongIssuer() throws Exception {
        final String token = signedToken((RSAPrivateKey) keyPair.getPrivate(), "https://evil.test",
                CLIENT_ID, "user-sub-1", true, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> provider.verifyIdToken(token))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("issuer");
    }

    @Test
    void rejectsMissingSubject() throws Exception {
        final String token = signedToken((RSAPrivateKey) keyPair.getPrivate(), ISSUER, CLIENT_ID,
                null, true, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> provider.verifyIdToken(token))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("sub");
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        final String token = signedToken((RSAPrivateKey) keyPair.getPrivate(), ISSUER, CLIENT_ID,
                "user-sub-1", true, Instant.now().minus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> provider.verifyIdToken(token))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("signature/expiry");
    }

    @Test
    void rejectsTamperedSignature() throws Exception {
        // Signed with a different key than the decoder trusts.
        final String token = signedToken((RSAPrivateKey) otherKeyPair.getPrivate(), ISSUER, CLIENT_ID,
                "user-sub-1", true, Instant.now().plus(1, ChronoUnit.HOURS));

        assertThatThrownBy(() -> provider.verifyIdToken(token))
                .isInstanceOf(IdentityProviderException.class)
                .hasMessageContaining("signature/expiry");
    }
}
