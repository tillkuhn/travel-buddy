package net.timafe.travel.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.InterceptingClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;

/**
 * Wires bearer-token authentication into Embabel's OpenAI-compatible HTTP client.
 *
 * <p>Embabel builds its {@code OpenAiApi} with a {@code RestClient} whose request factory is taken
 * from the Spring context ({@code ObjectProvider<ClientHttpRequestFactory>.getIfAvailable}). We
 * therefore expose a {@link ClientHttpRequestFactory} that attaches a freshly-minted
 * {@code Authorization: Bearer …} header on <em>every</em> outbound call — transparently handling
 * the short token lifetime without any code in the agent or service layer.
 *
 * <p>The whole configuration is only active when {@code gateway.auth.token-url} is set (i.e. when a
 * local override selects the remote gateway). Without it, no request factory bean is published and
 * Embabel falls back to its default unauthenticated client — the local ramalama setup keeps working.
 *
 * <p>If custom SSL certificates are configured via {@code gateway.auth.ssl}, they are set at the
 * JVM level to ensure all HTTPS connections (including those made deep inside Spring AI / Embabel)
 * trust the company certificates.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(name = "gateway.auth.token-url")
public class GatewayAuthConfig {

    private static final Logger log = LoggerFactory.getLogger(GatewayAuthConfig.class);

    public GatewayAuthConfig(GatewayProperties props, 
                             org.springframework.core.env.ConfigurableEnvironment environment,
                             @org.springframework.context.annotation.Lazy GatewayTokenService tokenService) {
        // Configure JVM-level SSL truststore if specified
        // This must happen early, before any HTTPS connections are made
        if (props.ssl() != null && props.ssl().trustStorePath() != null) {
            configureJvmTrustStore(props.ssl());
        }
        
        // Create a custom PropertySource that dynamically provides the OAuth2 token
        log.info("Installing dynamic OAuth2 token property source");
        org.springframework.core.env.PropertySource<?> dynamicTokenSource = new org.springframework.core.env.PropertySource<GatewayTokenService>("dynamic-oauth-token", tokenService) {
            @Override
            public Object getProperty(String name) {
                if ("embabel.agent.platform.models.openai.api-key".equals(name)) {
                    String token = getSource().getToken();
                    log.debug("PropertySource providing fresh OAuth2 token for openai.api-key");
                    return token;
                }
                return null;
            }
        };
        environment.getPropertySources().addFirst(dynamicTokenSource);
        
        log.info("Dynamic OAuth2 token property source installed - tokens will be automatically refreshed");
    }

    @Bean
    GatewayTokenService gatewayTokenService(GatewayProperties props) {
        // Token service now uses JVM-level SSL configuration
        return new GatewayTokenService(props, null);
    }
    
    /**
     * Provides a dynamic API key that returns the current OAuth2 bearer token.
     * OpenAI API sends the API key as "Authorization: Bearer <key>", so by providing
     * the OAuth2 token as the API key, we get automatic authentication.
     */
    @Bean("openAiApiKey")
    public org.springframework.ai.model.ApiKey dynamicApiKey(GatewayTokenService tokenService) {
        log.info("Creating dynamic API key bean that provides OAuth2 bearer tokens");
        return () -> {
            String token = tokenService.getToken();
            log.debug("Providing OAuth2 token as API key");
            return token;
        };
    }

    @Bean
    @org.springframework.context.annotation.Primary
    ClientHttpRequestFactory gatewayAuthRequestFactory(GatewayTokenService tokenService, GatewayProperties props) {
        // Use standard SimpleClientHttpRequestFactory - SSL is configured at JVM level
        SimpleClientHttpRequestFactory base = new SimpleClientHttpRequestFactory();
        base.setConnectTimeout(Duration.ofSeconds(10));
        base.setReadTimeout(Duration.ofSeconds(120));

        ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
            String token = tokenService.getToken();
            log.info("🔑 Interceptor adding Authorization header: {} {}", request.getMethod(), request.getURI());
            request.getHeaders().setBearerAuth(token);
            return execution.execute(request, body);
        };
        
        log.info("Created gateway auth request factory with bearer token interceptor");
        return new InterceptingClientHttpRequestFactory(base, List.of(authInterceptor));
    }

    /**
     * Configures the JVM-level SSL truststore for all HTTPS connections.
     *
     * <p>This sets system properties that affect the entire JVM, ensuring that all HTTPS
     * connections (including those made deep inside Spring AI, Embabel, or any other library)
     * will trust certificates from the custom truststore.
     *
     * <p>This approach is necessary because Embabel's OpenAI client creates its own internal
     * HTTP connections that don't use our custom ClientHttpRequestFactory beans.
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

        String absolutePath = truststorePath.toAbsolutePath().toString();
        
        log.info("Configuring JVM-level SSL truststore: {}", absolutePath);
        
        // Set JVM system properties for SSL truststore
        System.setProperty("javax.net.ssl.trustStore", absolutePath);
        if (sslConfig.trustStorePassword() != null) {
            System.setProperty("javax.net.ssl.trustStorePassword", sslConfig.trustStorePassword());
        }
        System.setProperty("javax.net.ssl.trustStoreType", sslConfig.trustStoreType());
        
        log.info("JVM SSL truststore configured - all HTTPS connections will use custom certificates");
        log.debug("  trustStore: {}", absolutePath);
        log.debug("  trustStoreType: {}", sslConfig.trustStoreType());
    }

    /**
     * Creates an SSLContext with a custom truststore containing company certificates.
     *
     * <p>This method is kept for reference but is no longer actively used since we configure
     * SSL at the JVM level instead. It may be useful for testing or alternative configurations.
     *
     * @param sslConfig SSL configuration containing truststore path and credentials
     * @return configured SSLContext
     * @throws Exception if truststore cannot be loaded or SSL context creation fails
     * @deprecated Use JVM-level configuration via {@link #configureJvmTrustStore} instead
     */
    @Deprecated
    private SSLContext createSslContext(GatewayProperties.Ssl sslConfig) throws Exception {
        Path truststorePath = Paths.get(sslConfig.trustStorePath());
        
        if (!Files.exists(truststorePath)) {
            throw new IllegalArgumentException(
                    "Truststore file not found: " + truststorePath.toAbsolutePath() +
                    ". Please ensure the company certificates are installed (see CERTIFICATES.md)"
            );
        }

        log.debug("Loading truststore from: {}", truststorePath.toAbsolutePath());

        // Load the custom truststore
        KeyStore trustStore = KeyStore.getInstance(sslConfig.trustStoreType());
        try (FileInputStream trustStoreStream = new FileInputStream(truststorePath.toFile())) {
            char[] password = sslConfig.trustStorePassword() != null 
                    ? sslConfig.trustStorePassword().toCharArray() 
                    : null;
            trustStore.load(trustStoreStream, password);
        }

        // Initialize TrustManager with the custom truststore
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
        );
        trustManagerFactory.init(trustStore);

        // Create and initialize SSLContext
        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, trustManagerFactory.getTrustManagers(), null);

        return sslContext;
    }
}
