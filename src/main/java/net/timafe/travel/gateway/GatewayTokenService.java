package net.timafe.travel.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Map;

/**
 * Obtains and caches a bearer token from the identity provider using the OAuth2
 * {@code client_credentials} grant.
 *
 * <p>Gateway tokens are typically valid for only a few minutes, so the token is cached and
 * transparently re-fetched shortly before it expires. This keeps a long-running server
 * authenticated without any external helper script or restarts.
 *
 * <p>This service uses its own {@link RestClient}, optionally configured with a custom SSL
 * context if the token endpoint uses internal/company certificates.
 */
public class GatewayTokenService {

    private static final Logger log = LoggerFactory.getLogger(GatewayTokenService.class);

    /** Re-fetch this many seconds before the reported expiry, to avoid racing the clock. */
    private static final long EXPIRY_MARGIN_SECONDS = 30;

    private final GatewayProperties props;
    private final RestClient restClient;

    private volatile String cachedToken;
    private volatile Instant renewAfter = Instant.EPOCH;

    public GatewayTokenService(GatewayProperties props, ClientHttpRequestFactory requestFactory) {
        this.props = props;
        // Use custom request factory if provided, otherwise use default
        if (requestFactory != null) {
            this.restClient = RestClient.builder().requestFactory(requestFactory).build();
            log.debug("GatewayTokenService initialized with custom request factory (SSL configured)");
        } else {
            this.restClient = RestClient.builder().build();
            log.debug("GatewayTokenService initialized with default request factory");
        }
    }

    /** Returns a valid bearer token, refreshing it if the cached one is missing or near expiry. */
    public synchronized String getToken() {
        if (cachedToken == null || !Instant.now().isBefore(renewAfter)) {
            refresh();
        }
        log.debug("Returning cached token (expires after {})", renewAfter);
        return cachedToken;
    }

    private void refresh() {
        if (props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new IllegalStateException(
                    "gateway.auth.client-secret is not set — copy application-local.properties.example "
                            + "to application-local.properties and fill in the credentials.");
        }
        log.debug("Requesting new AI gateway token from {}", props.tokenUrl());

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", props.clientId());
        form.add("client_secret", props.clientSecret());
        form.add("scope", props.scope());

        Map<String, Object> resp = restClient.post()
                .uri(props.tokenUrl())
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(new ParameterizedTypeReference<>() {
                });

        if (resp == null || !(resp.get("access_token") instanceof String token) || token.isBlank()) {
            throw new IllegalStateException("Token endpoint returned no access_token");
        }
        long expiresIn = resp.get("expires_in") instanceof Number n ? n.longValue() : 300L;

        this.cachedToken = token;
        this.renewAfter = Instant.now().plusSeconds(Math.max(1, expiresIn - EXPIRY_MARGIN_SECONDS));
        log.info("Obtained AI gateway token (expires_in={}s); will auto-renew after {}", expiresIn, renewAfter);
    }
}
