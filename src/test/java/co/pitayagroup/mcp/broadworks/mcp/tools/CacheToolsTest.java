package co.pitayagroup.mcp.broadworks.mcp.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import co.pitayagroup.mcp.broadworks.auth.session.UserInfo;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaConnectionFactory;
import co.pitayagroup.mcp.broadworks.mcp.AlpacaException;
import co.pitayagroup.mcp.broadworks.mcp.model.CacheFlushResult;

import co.ecg.alpaca.toolkit.model.BroadWorksServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;

class CacheToolsTest {

    private AlpacaConnectionFactory connectionFactory;
    private CacheTools tools;

    @BeforeEach
    void setUp() {
        connectionFactory = mock(AlpacaConnectionFactory.class);
        tools = new CacheTools(connectionFactory);
        authenticateAs("sub-1", "user@example.com");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String subject, String email) {
        final var principal = new DefaultOAuth2AuthenticatedPrincipal(subject,
                Map.of(UserInfo.SUBJECT_ATTRIBUTE, subject, UserInfo.EMAIL_ATTRIBUTE, email),
                java.util.List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, "n/a", java.util.List.of()));
    }

    @Test
    void flushCacheClearsTheAlpacaResponseCache() {
        final BroadWorksServer server = mock(BroadWorksServer.class);
        when(server.clearCache()).thenReturn(true);
        when(connectionFactory.connect(eq("sub-1"), eq("res-1"))).thenReturn(server);

        final CacheFlushResult result = tools.flushCache("res-1");

        assertThat(result.flushed()).isTrue();
        assertThat(result.message()).isEqualTo(CacheTools.FLUSHED_MESSAGE);
        verify(server).clearCache();
    }

    @Test
    void flushCacheReportsWhenClearFails() {
        final BroadWorksServer server = mock(BroadWorksServer.class);
        when(server.clearCache()).thenReturn(false);
        when(connectionFactory.connect(eq("sub-1"), eq(null))).thenReturn(server);

        final CacheFlushResult result = tools.flushCache(null);

        assertThat(result.flushed()).isFalse();
        assertThat(result.message()).isEqualTo(CacheTools.NOT_FLUSHED_MESSAGE);
    }

    @Test
    void flushCacheRequiresAuthenticatedUser() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> tools.flushCache(null))
                .isInstanceOf(AlpacaException.class)
                .hasMessage("No authenticated user in context");
    }
}
