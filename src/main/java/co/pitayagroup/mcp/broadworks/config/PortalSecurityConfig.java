package co.pitayagroup.mcp.broadworks.config;

import java.io.IOException;
import java.util.Set;

import co.pitayagroup.mcp.broadworks.auth.oauth.VerifiedEmailOidcUserService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.authentication.logout.HttpStatusReturningLogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Security for the browser-facing web portal ({@code /portal/**} SPA shell + {@code /api/portal/**}
 * JSON API), ordered {@code @Order(1)} so it takes precedence over the {@code @Order(2)} app
 * (Resource-Server) chain but sits below the Authorization-Server chain.
 *
 * <ul>
 *   <li>The SPA shell ({@code /portal/**}) is anonymous so the Angular app can render a login page.
 *       {@code /api/portal/**} still requires an interactive Google {@code oauth2Login} session
 *       (reusing {@link VerifiedEmailOidcUserService}, so unverified emails are rejected exactly as on
 *       the app chain).</li>
 *   <li>Unauthenticated JSON calls receive {@code 401} (not a login redirect) so the SPA can detect
 *       an expired session instead of receiving the bearer-token challenge the app chain would send.
 *       HTML entry-point handling is retained for any remaining authenticated HTML under this chain.</li>
 *   <li>{@code POST /api/portal/logout} invalidates the session and returns {@code 204}.</li>
 *   <li>CSRF uses a {@link CookieCsrfTokenRepository} readable by JavaScript, so Angular's
 *       {@code HttpClient} echoes the {@code XSRF-TOKEN} cookie as the {@code X-XSRF-TOKEN} header.</li>
 * </ul>
 *
 * <p>MCP authorization-code login is unaffected: Google callbacks live on the app chain, which uses
 * {@code defaultSuccessUrl("/portal", false)} so a SavedRequest to {@code /oauth2/authorize} still
 * wins over the portal landing page.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PortalSecurityConfig {

    @Bean
    @Order(1)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    public SecurityFilterChain portalSecurityFilterChain(HttpSecurity http) throws Exception {
        final CsrfTokenRequestAttributeHandler csrfRequestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .securityMatcher("/portal/**", "/api/portal/**")
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers("/portal/**").permitAll()
                        .anyRequest().authenticated())
                .oauth2Login(oauth2 -> oauth2
                        .userInfoEndpoint(userInfo -> userInfo
                                .userAuthoritiesMapper(SecurityConfig.factorStampingAuthoritiesMapper())
                                .oidcUserService(VerifiedEmailOidcUserService.create())))
                .logout(logout -> logout
                        .logoutUrl("/api/portal/logout")
                        .logoutSuccessHandler(new HttpStatusReturningLogoutSuccessHandler(HttpStatus.NO_CONTENT)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(portalAuthenticationEntryPoint()))
                // Cookie-based CSRF for the SPA: the token cookie is readable by JavaScript and echoed
                // back by Angular's HttpClient as the X-XSRF-TOKEN header.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .csrfTokenRequestHandler(csrfRequestHandler))
                // Materialize the CSRF token on every request so the XSRF-TOKEN cookie is emitted even
                // for safe (GET) calls the SPA makes before any mutating request.
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class);
        return http.build();
    }

    /**
     * {@code text/html} navigations (browser page loads) are redirected to Google login; all other
     * requests (XHR/JSON) receive a {@code 401} so the SPA can handle an unauthenticated state itself.
     */
    private static AuthenticationEntryPoint portalAuthenticationEntryPoint() {
        final MediaTypeRequestMatcher htmlMatcher =
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML);
        // Ignore */* so a fetch/XHR default Accept does not masquerade as a browser navigation.
        htmlMatcher.setIgnoredMediaTypes(Set.of(MediaType.ALL));

        final AuthenticationEntryPoint loginRedirect =
                new LoginUrlAuthenticationEntryPoint("/oauth2/authorization/google");
        final AuthenticationEntryPoint unauthorized =
                new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED);

        return (request, response, authException) -> {
            if (htmlMatcher.matches(request)) {
                loginRedirect.commence(request, response, authException);
            } else {
                unauthorized.commence(request, response, authException);
            }
        };
    }

    /**
     * Forces the deferred {@link CsrfToken} to be loaded so the {@link CookieCsrfTokenRepository}
     * writes the {@code XSRF-TOKEN} cookie on the response.
     */
    static final class CsrfCookieFilter extends OncePerRequestFilter {
        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                        FilterChain filterChain) throws ServletException, IOException {
            final CsrfToken csrfToken = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
            if (csrfToken != null) {
                // Accessing the token value triggers the repository to persist the cookie.
                csrfToken.getToken();
            }
            filterChain.doFilter(request, response);
        }
    }
}
