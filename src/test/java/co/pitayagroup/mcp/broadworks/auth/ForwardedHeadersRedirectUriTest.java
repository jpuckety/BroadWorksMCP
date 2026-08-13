package co.pitayagroup.mcp.broadworks.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression test for the OAuth2 login callback {@code redirect_uri} scheme when the server runs
 * behind a TLS-terminating Application Load Balancer.
 *
 * <p>The ALB terminates HTTPS and forwards plain HTTP to the container, adding
 * {@code X-Forwarded-Proto: https} (and {@code X-Forwarded-Port: 443}) while preserving the original
 * {@code Host}. Without honoring those forwarded headers Spring rebuilds the Google callback
 * {@code redirect_uri} from the internal request as {@code http://<host>/login/oauth2/code/google},
 * which Google rejects. With {@code server.forward-headers-strategy=framework} the request scheme is
 * corrected and the advertised {@code redirect_uri} becomes {@code https://...}.</p>
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY",
        "broadworks.oidc.client-id=test-google-client",
        "broadworks.oidc.client-secret=test-secret"
})
@AutoConfigureMockMvc
class ForwardedHeadersRedirectUriTest {

    private static final String EXTERNAL_HOST = "broadworks.mcp.ecg.co";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void googleRedirectUriUsesHttpsWhenBehindTlsTerminatingProxy() throws Exception {
        String expectedRedirectUri = "redirect_uri=https://" + EXTERNAL_HOST + "/login/oauth2/code/google";

        mockMvc.perform(get("/oauth2/authorization/google")
                        .header(HttpHeaders.HOST, EXTERNAL_HOST)
                        .header("X-Forwarded-Host", EXTERNAL_HOST)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "443")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("accounts.google.com")))
                .andExpect(header().string(HttpHeaders.LOCATION, containsString(expectedRedirectUri)));
    }

    @Test
    void googleRedirectUriIsNotDowngradedToHttp() throws Exception {
        String httpRedirectUri = "redirect_uri=http://" + EXTERNAL_HOST + "/login/oauth2/code/google";

        mockMvc.perform(get("/oauth2/authorization/google")
                        .header(HttpHeaders.HOST, EXTERNAL_HOST)
                        .header("X-Forwarded-Host", EXTERNAL_HOST)
                        .header("X-Forwarded-Proto", "https")
                        .header("X-Forwarded-Port", "443")
                        .accept(MediaType.TEXT_HTML))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, not(containsString(httpRedirectUri))));
    }
}
