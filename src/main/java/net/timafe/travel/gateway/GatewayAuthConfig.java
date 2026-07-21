package net.timafe.travel.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.AuthorizedClientServiceOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.InMemoryOAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.web.client.RestClient;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.time.Duration;
import javax.net.ssl.TrustManagerFactory;

/**
 * Wires OAuth2 {@code client_credentials} bearer-token authentication into Embabel's
 * OpenAI-compatible HTTP client, replacing the previous bespoke {@code GatewayProxyController} /
 * {@code GatewayTokenService} local-proxy approach with standard Spring Security OAuth2 client
 * machinery ({@link OAuth2AuthorizedClientManager} + {@link OAuth2ClientHttpRequestInterceptor}).
 *
 * <p>Spring AI (which Embabel builds on) normally reads its OpenAI-compatible {@code api-key}
 * <strong>once at startup</strong> and caches it for the JVM's lifetime — fine for static keys,
 * but broken for gateways that require short-lived OAuth2 tokens. Embabel's {@code
 * OpenAiModelsConfig} sidesteps this: it looks up an {@code ObjectProvider<RestClient.Builder>}
 * qualified {@code "aiModelRestClientBuilder"} and, if present, uses it verbatim to build the
 * underlying {@code OpenAiApi} client (see {@code OpenAiCompatibleModelFactory.createOpenAiApi()}
 * in the {@code embabel-agent-openai} sources). By publishing that exact qualified bean with an
 * {@link OAuth2ClientHttpRequestInterceptor} attached, every outbound HTTP call gets a
 * freshly-minted bearer token — no local proxy, no dynamic {@code PropertySource} trickery needed.
 *
 * <p>The whole configuration is only active when {@code gateway.auth.token-url} is set (i.e. when
 * a local override selects the remote gateway). Without it, no {@code aiModelRestClientBuilder}
 * bean is published and Embabel falls back to its default client — the local ramalama setup keeps
 * working unauthenticated.
 *
 * <p>If custom SSL certificates are configured via {@code gateway.auth.ssl}, they are set at the
 * JVM level (system properties) to ensure all HTTPS connections — including the token endpoint,
 * the gateway itself, and anything else in the JVM — trust the company CA. See CERTIFICATES.md.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(name = "gateway.auth.token-url")
public class GatewayAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthConfig.class);

    /** Fixed registration id used throughout — there's only ever one gateway registration. */
    private static final String REGISTRATION_ID = "gateway";

    public GatewayAuthConfig(GatewayProperties props) {
        if (props.ssl() != null && props.ssl().trustStorePath() != null) {
            configureJvmTrustStore(props.ssl());
        }
    }

    @Bean
    ClientRegistrationRepository gatewayClientRegistrationRepository(GatewayProperties props) {
        if (props.clientSecret() == null || props.clientSecret().isBlank()) {
            throw new IllegalStateException(
                    "gateway.auth.client-secret is not set — copy application-local.properties.example "
                            + "to application-local.properties and fill in the credentials.");
        }
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .tokenUri(props.tokenUrl())
                .clientId(props.clientId())
                .clientSecret(props.clientSecret())
                .scope(props.scope())
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                // The mock OIDC provider (and many simple client_credentials token endpoints)
                // expect client_id/client_secret as regular form fields in the POST body, not
                // Spring Security's default HTTP Basic Authorization header (client_secret_basic).
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    @Bean
    OAuth2AuthorizedClientService gatewayAuthorizedClientService(ClientRegistrationRepository registrations) {
        return new InMemoryOAuth2AuthorizedClientService(registrations);
    }

    @Bean
    OAuth2AuthorizedClientManager gatewayAuthorizedClientManager(
            ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clientService) {

        // Service-based, NOT DefaultOAuth2AuthorizedClientManager: Embabel's calls are not
        // guaranteed to run on a servlet-request thread, so there's no HttpServletRequest to key
        // the DefaultOAuth2AuthorizedClientManager's request-scoped lookup on.
        var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
        manager.setAuthorizedClientProvider(
                OAuth2AuthorizedClientProviderBuilder.builder()
                        .clientCredentials() // refreshes ~60s before expiry (default clock skew)
                        .build());
        log.info("Gateway OAuth2 authorized client manager configured (client_credentials grant)");
        return manager;
    }

    /**
     * Published under the exact qualifier ({@code "aiModelRestClientBuilder"}) Embabel's
     * {@code OpenAiModelsConfig} looks up via {@code ObjectProvider.getIfAvailable}, so this
     * builder — and therefore the OAuth2 bearer-token interceptor — is used for every LLM HTTP
     * call, without relying on Boot's generic {@code RestClientCustomizer} autoconfiguration
     * reaching Embabel's internals (which it does not: Embabel builds its {@code OpenAiApi}
     * client directly, not via the autoconfigured {@code RestClient.Builder}).
     */
    @Bean("aiModelRestClientBuilder")
    RestClient.Builder aiModelRestClientBuilder(OAuth2AuthorizedClientManager manager) {
        var oauth = new OAuth2ClientHttpRequestInterceptor(manager);
        oauth.setClientRegistrationIdResolver(request -> REGISTRATION_ID); // fixed id, no request-scoped attribute
        log.info("Created 'aiModelRestClientBuilder' RestClient.Builder with OAuth2 bearer-token interceptor");
        return RestClient.builder().requestInterceptor(oauth);
    }

    /**
     * Configures the JVM-level SSL truststore for all HTTPS connections.
     *
     * <p>This sets system properties that affect the entire JVM, ensuring that all HTTPS
     * connections (including those made deep inside Spring AI, Embabel, or any other library)
     * will trust certificates from the custom truststore. Necessary because Embabel's OpenAI
     * client creates its own internal HTTP connections that don't necessarily go through beans we
     * control.
     *
     * @param sslConfig SSL configuration containing truststore path and credentials
     */
    private void configureJvmTrustStore(GatewayProperties.Ssl sslConfig) {
        Path truststorePath = Paths.get(sslConfig.trustStorePath());

        if (!Files.exists(truststorePath)) {
            throw new IllegalArgumentException(
                    "Truststore file not found: " + truststorePath.toAbsolutePath() +
                            ". Please ensure the company certificates are installed (see CERTIFICATES.md)"
            );
        }

        // Fail fast with a clear error if the truststore/password/type combination is bad,
        // rather than surfacing a confusing PKIX error much later on the first HTTPS call.
        try (FileInputStream in = new FileInputStream(truststorePath.toFile())) {
            KeyStore trustStore = KeyStore.getInstance(sslConfig.trustStoreType());
            char[] password = sslConfig.trustStorePassword() != null
                    ? sslConfig.trustStorePassword().toCharArray()
                    : null;
            trustStore.load(in, password);
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).init(trustStore);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load truststore: " + truststorePath.toAbsolutePath(), e);
        }

        String absolutePath = truststorePath.toAbsolutePath().toString();
        log.info("Configuring JVM-level SSL truststore: {}", absolutePath);

        System.setProperty("javax.net.ssl.trustStore", absolutePath);
        if (sslConfig.trustStorePassword() != null) {
            System.setProperty("javax.net.ssl.trustStorePassword", sslConfig.trustStorePassword());
        }
        System.setProperty("javax.net.ssl.trustStoreType", sslConfig.trustStoreType());

        log.info("JVM SSL truststore configured - all HTTPS connections will use custom certificates");
    }
}
