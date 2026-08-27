package co.pitayagroup.mcp.broadworks.web.portal.dto;

import org.springframework.security.oauth2.core.oidc.user.OidcUser;

/**
 * Display-only identity for the portal header. This is not the tenant key ({@code UserInfo});
 * email/name/picture come from the Google ID token.
 */
public record MeResponse(String email, String name, String picture) {

    public static MeResponse from(OidcUser user) {
        return new MeResponse(user.getEmail(), user.getFullName(), user.getPicture());
    }
}
