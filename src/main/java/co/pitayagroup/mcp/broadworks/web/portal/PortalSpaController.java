package co.pitayagroup.mcp.broadworks.web.portal;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Serves the Angular single-page app shell for the web portal.
 *
 * <p>The compiled Angular assets are bundled under {@code classpath:/static/portal/} (built by the
 * {@code frontend-maven-plugin}) and served by Spring Boot's static resource handler. This controller
 * forwards portal navigation routes — {@code /portal} and client-side deep links whose final segment
 * carries no file extension (e.g. {@code /portal/new}, {@code /portal/{id}/edit}) — to
 * {@code index.html} so a page refresh or a bookmarked deep link still loads the SPA. Requests for
 * actual asset files (whose last segment contains a {@code .}) are left to the static resource
 * handler.</p>
 *
 * <p>The route depth is enumerated because Spring's {@code PathPattern} does not allow a {@code **}
 * segment in the middle of a pattern; the portal's client-side routes are shallow (at most a few
 * segments), so the listed patterns cover them.</p>
 */
@Controller
public class PortalSpaController {

    @GetMapping({
            "/portal",
            "/portal/",
            "/portal/{p1:[^.]*}",
            "/portal/{p1}/{p2:[^.]*}",
            "/portal/{p1}/{p2}/{p3:[^.]*}"
    })
    public String forwardToSpaShell() {
        return "forward:/portal/index.html";
    }
}
