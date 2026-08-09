package com.broadworks.mcp.auth.session;

import java.time.Instant;
import java.util.List;

import com.broadworks.mcp.auth.store.RegisteredClientRecord;
import com.broadworks.mcp.auth.store.SessionStore;
import com.broadworks.mcp.config.AuthTokenProperties;

import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.OAuth2TokenFormat;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;

/**
 * {@link RegisteredClientRepository} backed by the pluggable {@link SessionStore}.
 *
 * <p>Persists dynamically registered clients durably (RFC 7591, 90-day lifetime). Regardless of what
 * SAS asks to persist, clients are always materialised as <b>public</b> (no client secret,
 * {@code none} auth), PKCE-required, with <b>opaque (REFERENCE)</b> access tokens and the configured
 * TTLs. The registered client id and internal id are the same value.</p>
 */
@RequiredArgsConstructor
public class StoreBackedRegisteredClientRepository implements RegisteredClientRepository {

    private static final List<String> DEFAULT_SCOPES = List.of("openid", "email", "profile");

    private final SessionStore sessionStore;
    private final AuthTokenProperties tokenProperties;

    @Override
    public void save(RegisteredClient registeredClient) {
        final Instant createdAt = registeredClient.getClientIdIssuedAt() != null
                ? registeredClient.getClientIdIssuedAt()
                : Instant.now();
        final RegisteredClientRecord record = new RegisteredClientRecord(
                registeredClient.getClientId(),
                registeredClient.getClientName(),
                List.copyOf(registeredClient.getRedirectUris()),
                List.copyOf(registeredClient.getScopes()),
                registeredClient.getAuthorizationGrantTypes().stream()
                        .map(AuthorizationGrantType::getValue).toList(),
                RegisteredClientRecord.PUBLIC_CLIENT_AUTH_METHOD,
                createdAt,
                createdAt.plus(tokenProperties.registeredClientTtl()));
        sessionStore.saveClient(record);
    }

    @Override
    public RegisteredClient findById(String id) {
        return sessionStore.getClient(id).map(this::toRegisteredClient).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return sessionStore.getClient(clientId).map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(RegisteredClientRecord record) {
        final List<String> scopes = record.scopes().isEmpty() ? DEFAULT_SCOPES : record.scopes();
        final RegisteredClient.Builder builder = RegisteredClient.withId(record.clientId())
                .clientId(record.clientId())
                .clientIdIssuedAt(record.createdAt())
                .clientName(record.clientName() != null ? record.clientName() : record.clientId())
                // Public client: no secret, no client authentication.
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)              // PKCE mandatory
                        .requireAuthorizationConsent(false)
                        .build())
                .tokenSettings(TokenSettings.builder()
                        .accessTokenFormat(OAuth2TokenFormat.REFERENCE) // opaque access tokens
                        .accessTokenTimeToLive(tokenProperties.accessTokenTtl())
                        .refreshTokenTimeToLive(tokenProperties.refreshTokenTtl())
                        .authorizationCodeTimeToLive(tokenProperties.authorizationCodeTtl())
                        .reuseRefreshTokens(false)
                        .build());
        record.redirectUris().forEach(builder::redirectUri);
        scopes.forEach(builder::scope);
        return builder.build();
    }
}
