package co.pitayagroup.mcp.broadworks.web.portal;

import co.pitayagroup.mcp.broadworks.web.portal.dto.MeResponse;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Session identity for the Angular portal. Authenticated via the Google {@code oauth2Login} cookie
 * session; unauthenticated callers receive {@code 401} from the portal security chain.
 */
@RestController
@RequestMapping("/api/portal")
public class PortalMeController {

    @GetMapping("/me")
    public MeResponse me(@AuthenticationPrincipal OidcUser user) {
        return MeResponse.from(user);
    }
}
