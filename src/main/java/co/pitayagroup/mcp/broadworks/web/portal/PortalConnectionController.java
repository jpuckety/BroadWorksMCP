package co.pitayagroup.mcp.broadworks.web.portal;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import co.pitayagroup.mcp.broadworks.auth.session.UserContext;
import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.auth.store.AlpacaResource;
import co.pitayagroup.mcp.broadworks.auth.store.ResourceStore;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.HostAllowlist;
import co.pitayagroup.mcp.broadworks.mcp.tools.ConnectionValidation;
import co.pitayagroup.mcp.broadworks.web.portal.dto.ConnectionRequest;
import co.pitayagroup.mcp.broadworks.web.portal.dto.ConnectionResponse;
import co.pitayagroup.mcp.broadworks.web.portal.dto.PasswordRequest;

import jakarta.validation.Valid;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JSON REST API backing the Angular web portal. Every operation is scoped to the authenticated user's
 * {@code subject} (the same key the MCP tools use), so connections created via MCP appear here and
 * vice-versa. Non-secret fields are validated and SSRF-screened via the shared
 * {@link ConnectionValidation}; passwords are set out-of-band and never returned to the browser.
 */
@Slf4j
@RestController
@RequestMapping("/api/portal/connections")
public class PortalConnectionController {

    private final ResourceStore resourceStore;
    private final HostAllowlist hostAllowlist;

    public PortalConnectionController(ResourceStore resourceStore, HostAllowlist hostAllowlist) {
        this.resourceStore = resourceStore;
        this.hostAllowlist = hostAllowlist;
    }

    @GetMapping
    public List<ConnectionResponse> list() {
        final String subject = currentSubject();
        return resourceStore.listForUser(subject).stream()
                .map(ConnectionResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public ConnectionResponse get(@PathVariable String id) {
        return ConnectionResponse.from(requireOwned(currentSubject(), id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConnectionResponse create(@Valid @RequestBody ConnectionRequest request) {
        final String subject = currentSubject();
        ConnectionValidation.validate(hostAllowlist, request.hostname(), request.port(), request.username());

        final String resourceId = deriveResourceId(request.displayName());
        final String displayName = (request.displayName() == null || request.displayName().isBlank())
                ? resourceId : request.displayName();
        final String password = (request.password() == null || request.password().isBlank())
                ? "" : request.password();

        final AlpacaResource resource = new AlpacaResource(
                resourceId,
                displayName,
                request.hostname().trim(),
                request.port(),
                request.username(),
                password);
        resourceStore.put(subject, resource);
        log.info("Portal created BroadWorks connection resourceId={} host={} (needsPassword={})",
                resourceId, resource.hostname(), password.isBlank());
        return ConnectionResponse.from(resource);
    }

    @PutMapping("/{id}")
    public ConnectionResponse update(@PathVariable String id, @Valid @RequestBody ConnectionRequest request) {
        final String subject = currentSubject();
        final AlpacaResource existing = requireOwned(subject, id);
        ConnectionValidation.validate(hostAllowlist, request.hostname(), request.port(), request.username());

        // A blank/absent password field on update means "leave the existing secret unchanged"; only a
        // non-blank value replaces it. Dedicated password changes go through the password endpoint.
        final String password = (request.password() == null || request.password().isBlank())
                ? existing.password() : request.password();
        final String displayName = (request.displayName() == null || request.displayName().isBlank())
                ? existing.displayName() : request.displayName();

        final AlpacaResource updated = new AlpacaResource(
                existing.resourceId(),
                displayName,
                request.hostname().trim(),
                request.port(),
                request.username(),
                password);
        resourceStore.put(subject, updated);
        log.info("Portal updated BroadWorks connection resourceId={} host={}", id, updated.hostname());
        return ConnectionResponse.from(updated);
    }

    @PutMapping("/{id}/password")
    public ConnectionResponse setPassword(@PathVariable String id, @Valid @RequestBody PasswordRequest request) {
        final String subject = currentSubject();
        final AlpacaResource existing = requireOwned(subject, id);

        final AlpacaResource updated = new AlpacaResource(
                existing.resourceId(),
                existing.displayName(),
                existing.hostname(),
                existing.port(),
                existing.username(),
                request.password());
        resourceStore.put(subject, updated);
        log.info("Portal set password for BroadWorks connection resourceId={}", id);
        return ConnectionResponse.from(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        final String subject = currentSubject();
        resourceStore.delete(subject, id);
        log.info("Portal deleted BroadWorks connection resourceId={}", id);
    }

    /**
     * Maps validation/SSRF failures ({@link AlpacaException}) to {@code 400 Bad Request} with a
     * secret-free message the SPA can surface.
     */
    @ExceptionHandler(AlpacaException.class)
    public ResponseEntity<Map<String, String>> handleAlpacaException(AlpacaException ex) {
        return ResponseEntity.badRequest().body(Map.of("message", ex.getMessage()));
    }

    private AlpacaResource requireOwned(String subject, String id) {
        return resourceStore.get(subject, id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No such connection: " + id));
    }

    private static String deriveResourceId(String displayName) {
        final String slug = displayName == null ? "" : displayName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        return slug.isBlank() ? UUID.randomUUID().toString() : slug;
    }

    private static String currentSubject() {
        final UserInfo user = UserContext.current()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "No authenticated user in context"));
        return user.subject();
    }
}
