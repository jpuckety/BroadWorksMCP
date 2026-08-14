package co.pitayagroup.mcp.broadworks.auth.oauth;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Shared OIDC user service that rejects Google logins whose {@code email_verified} claim is not
 * {@code true}.
 *
 * <p>Used by both interactive-login filter chains — the Resource-Server/app chain and the web-portal
 * chain — so the same identity-verification rule applies regardless of which browser entry point the
 * user arrives through.</p>
 */
public final class VerifiedEmailOidcUserService {

    private VerifiedEmailOidcUserService() {
    }

    /**
     * @return an {@link OAuth2UserService} that loads the OIDC user and refuses unverified emails.
     */
    public static OAuth2UserService<OidcUserRequest, OidcUser> create() {
        final OidcUserService delegate = new OidcUserService();
        return userRequest -> {
            final OidcUser oidcUser = delegate.loadUser(userRequest);
            if (!Boolean.TRUE.equals(oidcUser.getEmailVerified())) {
                throw new OAuth2AuthenticationException(
                        new OAuth2Error("email_not_verified"),
                        "Identity verification failed: your Google email address is not verified.");
            }
            return oidcUser;
        };
    }
}
