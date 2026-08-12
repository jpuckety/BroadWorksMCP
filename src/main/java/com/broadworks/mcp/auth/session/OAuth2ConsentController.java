package com.broadworks.mcp.auth.session;

import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.stereotype.Controller;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * MCP authorization-server consent page. Shows the DCR client identity and requested scopes so the
 * user can approve access (confused-deputy mitigation with static Google client + open DCR).
 */
@Controller
public class OAuth2ConsentController {

    private final RegisteredClientRepository registeredClientRepository;

    public OAuth2ConsentController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @GetMapping(path = "/oauth2/consent", produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String consent(Principal principal,
                          @RequestParam("client_id") String clientId,
                          @RequestParam("scope") String scope,
                          @RequestParam("state") String state) {
        final RegisteredClient registeredClient = registeredClientRepository.findByClientId(clientId);
        if (registeredClient == null) {
            throw new IllegalArgumentException("Unknown client: " + clientId);
        }

        final List<String> scopesToApprove = new ArrayList<>();
        for (String requestedScope : StringUtils.delimitedListToStringArray(scope, " ")) {
            if (registeredClient.getScopes().contains(requestedScope)) {
                scopesToApprove.add(requestedScope);
            }
        }

        final String clientName = registeredClient.getClientName() != null
                ? registeredClient.getClientName()
                : clientId;
        final String principalName = principal != null ? principal.getName() : "unknown";
        final String redirectUris = String.join(", ", registeredClient.getRedirectUris());

        final StringBuilder scopesHtml = new StringBuilder();
        final StringBuilder scopeInputs = new StringBuilder();
        for (String s : scopesToApprove) {
            scopesHtml.append("<li>").append(escape(s)).append("</li>");
            scopeInputs.append("<input type=\"hidden\" name=\"scope\" value=\"")
                    .append(escapeAttr(s)).append("\">");
        }

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <title>Consent required</title>
                  <style>
                    body { font-family: system-ui, sans-serif; max-width: 36rem; margin: 2rem auto; padding: 0 1rem; }
                    h1 { font-size: 1.4rem; }
                    .meta { color: #444; font-size: 0.95rem; }
                    ul { padding-left: 1.2rem; }
                    button { margin-right: 0.5rem; margin-top: 1rem; padding: 0.5rem 1rem; }
                    .primary { background: #1a73e8; color: #fff; border: 0; border-radius: 4px; cursor: pointer; }
                    .secondary { background: #eee; border: 1px solid #ccc; border-radius: 4px; cursor: pointer; }
                  </style>
                </head>
                <body>
                  <h1>Consent required</h1>
                  <p class="meta">
                    Application <strong>%s</strong> wants access to your account
                    <strong>%s</strong>.
                  </p>
                  <p class="meta">Client id: <code>%s</code></p>
                  <p class="meta">Redirect URI(s): <code>%s</code></p>
                  <p>Requested permissions:</p>
                  <ul>%s</ul>
                  <form method="post" action="/oauth2/authorize">
                    <input type="hidden" name="client_id" value="%s">
                    <input type="hidden" name="state" value="%s">
                    %s
                    <button class="primary" type="submit">Allow access</button>
                    <button class="secondary" type="button" onclick="history.back()">Cancel</button>
                  </form>
                </body>
                </html>
                """.formatted(
                escape(clientName),
                escape(principalName),
                escape(clientId),
                escape(redirectUris),
                scopesHtml,
                escapeAttr(clientId),
                escapeAttr(state),
                scopeInputs);
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeAttr(String value) {
        return escape(value).replace("'", "&#39;");
    }
}
