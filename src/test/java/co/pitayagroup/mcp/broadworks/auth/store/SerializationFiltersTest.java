package co.pitayagroup.mcp.broadworks.auth.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import tools.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUserAuthority;
import org.springframework.session.MapSession;
import org.springframework.session.SessionRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Guards the JEP-290 allow-lists in {@link SerializationFilters} against the object graphs the login
 * flow really stores, because a single missing entry is silently fatal: the DynamoDB-backed
 * repositories log "Discarding unreadable ..." and drop the payload, which used to send browsers into
 * an endless {@code /oauth2/authorize -> Google -> callback -> /oauth2/authorize} redirect loop.
 *
 * <p>Two graphs are covered: the pre-login session captured from a real authorization request (saved
 * request + {@code OAuth2AuthorizationRequest}), and the post-login {@code SecurityContext} carrying
 * an OIDC principal - whose id token holds the {@code iss} claim as a {@link URL}, the entry that was
 * missing.</p>
 */
@SpringBootTest(properties = {
        "broadworks.storage.backend=IN_MEMORY",
        "broadworks.oidc.client-id=test-google-client",
        "broadworks.oidc.client-secret=test-secret"
})
@AutoConfigureMockMvc
class SerializationFiltersTest {

    private static final String SESSION_COOKIE = "SESSION";
    private static final String SECURITY_CONTEXT_ATTR = "SPRING_SECURITY_CONTEXT";
    private static final String SAVED_REQUEST_ATTR = "SPRING_SECURITY_SAVED_REQUEST";
    private static final String AUTHORIZATION_REQUEST_ATTR =
            "org.springframework.security.oauth2.client.web."
                    + "HttpSessionOAuth2AuthorizationRequestRepository.AUTHORIZATION_REQUEST";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SessionRepository<MapSession> sessionRepository;

    @Test
    void preLoginSessionAttributesSurviveTheHttpSessionFilter() throws Exception {
        final MapSession session = performAuthorizeAndLoginRedirect();

        final Map<String, Object> attributes = new HashMap<>();
        for (String name : session.getAttributeNames()) {
            attributes.put(name, session.getAttribute(name));
        }
        // The interrupted authorization request and the saved request are what the callback needs.
        assertThat(attributes).containsKeys(SAVED_REQUEST_ATTR, AUTHORIZATION_REQUEST_ATTR);

        assertThat(roundTripSessionAttributes(attributes))
                .containsOnlyKeys(attributes.keySet().toArray(new String[0]));
    }

    @Test
    void authenticatedOidcSecurityContextSurvivesTheHttpSessionFilter() throws Exception {
        final Map<String, Object> attributes =
                Map.of(SECURITY_CONTEXT_ATTR, authenticatedSecurityContext());

        final Map<String, Object> reloaded = roundTripSessionAttributes(attributes);

        final SecurityContext context = (SecurityContext) reloaded.get(SECURITY_CONTEXT_ATTR);
        assertThat(context).isNotNull();
        final DefaultOidcUser user = (DefaultOidcUser) context.getAuthentication().getPrincipal();
        assertThat(user.getSubject()).isEqualTo("sub-123");
        // The iss claim is a java.net.URL (Spring Security's OidcIdTokenDecoderFactory converts it).
        assertThat(user.getIdToken().getIssuer()).isInstanceOf(URL.class);
    }

    @Test
    void authenticatedOidcPrincipalSurvivesTheAuthorizationFilter() throws Exception {
        // The same principal is stored as an OAuth2Authorization attribute, so it is read back
        // through AUTHORIZATION_FILTER as well.
        final OAuth2AuthenticationToken principal =
                (OAuth2AuthenticationToken) authenticatedSecurityContext().getAuthentication();

        final OAuth2AuthenticationToken reloaded = AuthorizationSerialization.deserialize(
                AuthorizationSerialization.serialize(principal), OAuth2AuthenticationToken.class);

        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getName()).isEqualTo("sub-123");
    }

    /**
     * Drives a real unauthenticated authorization request followed by the Google login initiation, so
     * the session holds exactly what production stores at that point, and returns that session.
     */
    private MapSession performAuthorizeAndLoginRedirect() throws Exception {
        final String registration = """
                {"redirect_uris":["http://127.0.0.1:8123/callback"],"client_name":"Test"}""";
        final MvcResult registered = mockMvc.perform(post("/oauth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registration))
                .andReturn();
        final String clientId = objectMapper.readTree(registered.getResponse().getContentAsString())
                .get("client_id").asText();

        final String authorize = "/oauth2/authorize?response_type=code"
                + "&client_id=" + clientId
                + "&redirect_uri=http://127.0.0.1:8123/callback"
                + "&scope=openid"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
                + "&code_challenge_method=S256";
        final MvcResult result = mockMvc.perform(get(authorize).accept(MediaType.TEXT_HTML)).andReturn();
        final String cookie = result.getResponse().getCookie(SESSION_COOKIE).getValue();

        mockMvc.perform(get("/oauth2/authorization/google")
                .accept(MediaType.TEXT_HTML)
                .cookie(new Cookie(SESSION_COOKIE, cookie)));

        final MapSession session =
                sessionRepository.findById(new String(Base64.getDecoder().decode(cookie)));
        assertThat(session).isNotNull();
        return session;
    }

    /** Mirrors what {@code DynamoDbHttpSessionRepository} does with the session attribute map. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> roundTripSessionAttributes(Map<String, Object> attributes)
            throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) {
            out.writeObject(new HashMap<>(attributes));
        }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            in.setObjectInputFilter(SerializationFilters.HTTP_SESSION_FILTER);
            return (Map<String, Object>) in.readObject();
        }
    }

    private static SecurityContext authenticatedSecurityContext() throws Exception {
        final OidcIdToken idToken = OidcIdToken.withTokenValue("id-token")
                .claim("iss", new URL("https://accounts.google.com"))
                .subject("sub-123")
                .audience(List.of("test-google-client"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("email", "user@example.com")
                .claim("email_verified", Boolean.TRUE)
                .build();
        final OidcUserInfo userInfo = OidcUserInfo.builder()
                .subject("sub-123")
                .email("user@example.com")
                .picture("https://lh3.googleusercontent.com/a/example")
                .build();
        final DefaultOidcUser user = new DefaultOidcUser(
                List.of(new SimpleGrantedAuthority("ROLE_USER"), new OidcUserAuthority(idToken, userInfo)),
                idToken, userInfo, "sub");
        return new SecurityContextImpl(
                new OAuth2AuthenticationToken(user, user.getAuthorities(), "google"));
    }
}
