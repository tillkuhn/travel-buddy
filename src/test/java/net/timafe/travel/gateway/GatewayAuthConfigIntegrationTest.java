package net.timafe.travel.gateway;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.security.oauth2.client.OAuth2ClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.RestClient;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the OAuth2 client-credentials wiring in {@link GatewayAuthConfig}.
 *
 * <p>Uses a purely local, in-JVM WireMock server to stand in for both the OAuth2 token endpoint
 * and the downstream "AI gateway" — no real network, no remote services, no dependency on the
 * Python mock or a running LLM backend. This verifies the full request flow: token acquisition
 * via client_credentials, and that the resulting bearer token is attached to outbound requests
 * built from the {@code aiModelRestClientBuilder} bean.
 */
class GatewayAuthConfigIntegrationTest {

    private WireMockServer wireMock;
    private ApplicationContextRunner contextRunner;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(0); // dynamic port
        wireMock.start();

        contextRunner = new ApplicationContextRunner()
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(
                        OAuth2ClientAutoConfiguration.class))
                .withUserConfiguration(GatewayAuthConfig.class)
                .withPropertyValues(
                        "gateway.auth.token-url=" + wireMock.baseUrl() + "/token",
                        "gateway.auth.client-id=test-client",
                        "gateway.auth.client-secret=test-secret",
                        "gateway.auth.scope=gateway.read"
                );
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @Test
    void attachesFreshBearerTokenObtainedViaClientCredentialsToOutboundRequests() {
        wireMock.stubFor(post(urlEqualTo("/token"))
                .willReturn(okJson("""
                        {
                          "access_token": "mocked-access-token-abc123",
                          "token_type": "Bearer",
                          "expires_in": 3600
                        }
                        """)));

        wireMock.stubFor(get(urlEqualTo("/ai/chat/completions"))
                .willReturn(aResponse().withStatus(HttpStatus.OK.value()).withBody("ok")));

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();

            RestClient.Builder builder = context.getBean("aiModelRestClientBuilder", RestClient.Builder.class);
            RestClient restClient = builder.build();

            String response = restClient.get()
                    .uri(wireMock.baseUrl() + "/ai/chat/completions")
                    .retrieve()
                    .body(String.class);

            assertThat(response).isEqualTo("ok");
        });

        // The token endpoint was called with the client_credentials grant, form-encoded
        // (CLIENT_SECRET_POST), not HTTP Basic auth.
        wireMock.verify(postRequestedFor(urlEqualTo("/token"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=test-client"))
                .withRequestBody(containing("client_secret=test-secret"))
                .withRequestBody(containing("scope=gateway.read")));

        // The AI gateway request carried the bearer token minted from the token endpoint.
        wireMock.verify(getRequestedFor(urlEqualTo("/ai/chat/completions"))
                .withHeader("Authorization", equalTo("Bearer mocked-access-token-abc123")));
    }

    @Test
    void beanIsNotPublishedWhenTokenUrlPropertyIsAbsent() {
        new ApplicationContextRunner()
                .withUserConfiguration(GatewayAuthConfig.class)
                .run(context -> assertThat(context).doesNotHaveBean("aiModelRestClientBuilder"));
    }
}
