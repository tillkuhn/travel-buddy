package net.timafe.travel.gateway;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OAuth2 client-credentials settings used to obtain a short-lived bearer token for a
 * remote, OpenAI-compatible AI gateway that sits behind an identity provider (Keycloak).
 *
 * <p>These properties are only set in the gitignored {@code application-local.properties}
 * (see {@code application-local.properties.example}); when {@code gateway.auth.token-url} is
 * absent the whole gateway-auth machinery is disabled and the app talks to a local,
 * unauthenticated LLM endpoint instead.
 *
 * <p>The {@code ssl} configuration is <strong>optional</strong> and only needed when the gateway
 * uses certificates from a custom/internal CA. If omitted, the default JVM truststore is used.
 */
@ConfigurationProperties(prefix = "gateway.auth")
public record GatewayProperties(
        String tokenUrl,
        String clientId,
        String clientSecret,
        String scope,
        Ssl ssl
) {
    /**
     * SSL/TLS configuration for custom certificate trust (optional).
     *
     * <p>Only required when connecting to gateways that use certificates issued by
     * internal/corporate CAs not present in the default JVM truststore. If not configured,
     * standard SSL certificate validation using the JVM's default truststore is used.
     *
     * @param trustStorePath   Path to custom truststore file (e.g., "certs/company-truststore.jks").
     *                         Can be absolute or relative to application working directory.
     *                         If null, default JVM truststore is used.
     * @param trustStorePassword Password for the truststore file (can be null for password-less stores).
     * @param trustStoreType   Truststore format (JKS, PKCS12, etc.). Defaults to JKS if not specified.
     */
    public record Ssl(
            String trustStorePath,
            String trustStorePassword,
            String trustStoreType
    ) {
        public String trustStoreType() {
            return trustStoreType != null ? trustStoreType : "JKS";
        }
    }
}
