package co.pitayagroup.mcp.broadworks.auth.session;

import java.util.Optional;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2AuthenticatedPrincipal;

/**
 * Helper for obtaining the authenticated {@link UserInfo} from the current security context.
 *
 * <p>Mirrors the blueprint's {@code UserFromContext(ctx)} helper. Works both for Resource-Server
 * bearer authentication (where the principal is an {@link OAuth2AuthenticatedPrincipal} carrying
 * {@code sub}/{@code email} attributes) and for any authentication whose principal exposes those
 * attributes.</p>
 */
public final class UserContext {

    private UserContext() {
    }

    /**
     * @return the {@link UserInfo} for the current security context, if authenticated.
     */
    public static Optional<UserInfo> current() {
        return fromAuthentication(SecurityContextHolder.getContext().getAuthentication());
    }

    /**
     * @return the {@link UserInfo} derived from the given authentication, if it carries a subject.
     */
    public static Optional<UserInfo> fromAuthentication(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return Optional.empty();
        }
        final Object principal = authentication.getPrincipal();
        if (principal instanceof OAuth2AuthenticatedPrincipal oauthPrincipal) {
            final String subject = oauthPrincipal.getAttribute(UserInfo.SUBJECT_ATTRIBUTE);
            if (subject != null && !subject.isBlank()) {
                return Optional.of(new UserInfo(subject, oauthPrincipal.getAttribute(UserInfo.EMAIL_ATTRIBUTE)));
            }
        }
        return Optional.empty();
    }
}
