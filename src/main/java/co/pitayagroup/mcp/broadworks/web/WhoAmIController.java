package co.pitayagroup.mcp.broadworks.web;

import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Secured diagnostic endpoint that echoes the authenticated {@link UserInfo}. Requires a valid
 * bearer token (enforced by the Resource-Server chain) and is a convenient way to confirm identity
 * injection without invoking an MCP tool.
 */
@RestController
public class WhoAmIController {

    @GetMapping(path = "/whoami", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> whoami() {
        return UserContext.current()
                .map(user -> ResponseEntity.ok(Map.of(
                        "subject", user.subject(),
                        "email", user.email() == null ? "" : user.email())))
                .orElseGet(() -> ResponseEntity.status(401).build());
    }
}
