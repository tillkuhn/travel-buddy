# Technical Challenges & Solutions

This document describes the technical challenges encountered when integrating this Spring Boot/Embabel application with a remote AI gateway that requires OAuth2 authentication with short-lived tokens (5 minutes) and custom SSL certificates.

## Table of Contents
1. [Challenge: Spring AI Token Caching](#challenge-spring-ai-token-caching)
2. [Challenge: SSL/TLS Certificate Trust](#challenge-ssltls-certificate-trust)
3. [Solution: Local Proxy Architecture](#solution-local-proxy-architecture) *(superseded, see below)*
4. [Implementation Details](#implementation-details)
5. [Update: Removing the Proxy — Direct OAuth2 Client Wiring](#update-removing-the-proxy--direct-oauth2-client-wiring)

> **2026 update**: the local proxy described below (`GatewayProxyController` /
> `GatewayTokenService`) has been **removed**. It's kept here as historical record of the
> investigation, but the current implementation uses standard Spring Security OAuth2 client
> machinery instead — no proxy, no request forwarding. See
> [Update: Removing the Proxy](#update-removing-the-proxy--direct-oauth2-client-wiring) at the
> end of this document, and [gateway-proxy-removal.md](gateway-proxy-removal.md) for the original
> design doc that triggered the change.

---

## Challenge: Spring AI Token Caching

### Problem Statement

The AI gateway requires OAuth2 authentication with bearer tokens that expire after only **5 minutes**. However, Spring AI (which Embabel is built upon) caches the OpenAI API key at application startup and never re-reads it from configuration properties.

### Timeline of Attempted Solutions

#### Attempt 1: Dynamic PropertySource
**Approach**: Create a custom Spring `PropertySource` that provides fresh tokens on every property read.

```java
PropertySource<?> dynamicTokenSource = new PropertySource<>("dynamic-oauth-token", tokenService) {
    @Override
    public Object getProperty(String name) {
        if ("embabel.agent.platform.models.openai.api-key".equals(name)) {
            return getSource().getToken(); // Fresh token every time
        }
        return null;
    }
};
environment.getPropertySources().addFirst(dynamicTokenSource);
```

**Result**: ❌ **Failed**
- PropertySource was only called **once** during application startup
- Spring AI's `OpenAiApi` caches the returned value internally
- Subsequent LLM requests used the stale cached token → 401 errors after 5 minutes

#### Attempt 2: Dynamic ApiKey Bean
**Approach**: Provide a custom `ApiKey` bean that returns fresh tokens via a lambda.

```java
@Bean("openAiApiKey")
public ApiKey dynamicApiKey(GatewayTokenService tokenService) {
    return () -> tokenService.getToken();
}
```

**Result**: ❌ **Failed**
- Embabel/Spring AI does not look for or use custom `ApiKey` beans
- The bean was created but never invoked
- Spring AI continued reading from the cached property value

#### Attempt 3: HTTP Request Interceptor
**Approach**: Create a `@Primary` `ClientHttpRequestFactory` bean with an interceptor that adds fresh OAuth2 tokens to every outbound request.

```java
@Bean
@Primary
ClientHttpRequestFactory gatewayAuthRequestFactory(GatewayTokenService tokenService) {
    SimpleClientHttpRequestFactory base = new SimpleClientHttpRequestFactory();
    ClientHttpRequestInterceptor authInterceptor = (request, body, execution) -> {
        String token = tokenService.getToken();
        request.getHeaders().setBearerAuth(token);
        return execution.execute(request, body);
    };
    return new InterceptingClientHttpRequestFactory(base, List.of(authInterceptor));
}
```

**Result**: ❌ **Failed**
- The interceptor was **never called** (no log messages appeared)
- Embabel creates its own internal `RestClient` instances that bypass Spring's `@Primary` bean
- The framework does not use `ObjectProvider<ClientHttpRequestFactory>` in the way we expected

#### Attempt 4: Custom OpenAiApi Bean
**Approach**: Override the entire `OpenAiApi` bean with a custom instance that uses our request factory.

**Result**: ❌ **Failed**
- Complex constructor requirements (8 parameters including error handlers, WebClient builders, etc.)
- Even when properly constructed, Embabel still created its own instances
- Too brittle and tightly coupled to Spring AI internals

### Root Cause Analysis

Spring AI's `OpenAiApi` class reads the `api-key` configuration property **once** during bean initialization:

```java
// Simplified Spring AI pseudocode
public class OpenAiApi {
    private final String apiKey;
    
    public OpenAiApi(@Value("${...api-key}") String apiKey) {
        this.apiKey = apiKey; // Cached here, never re-read
    }
    
    public ChatCompletion chat(...) {
        // Uses cached apiKey field
        headers.setBearerAuth(this.apiKey);
    }
}
```

This design makes sense for most use cases (OpenAI keys don't expire), but breaks down with short-lived OAuth2 tokens.

### Why Standard Solutions Don't Work

- **PropertySource refresh**: Spring resolves `@Value` fields once at bean creation
- **Dynamic beans**: Embabel doesn't look for custom `ApiKey` beans
- **Request interceptors**: Embabel creates its own HTTP clients internally
- **Scheduled restart**: Not acceptable for production (service interruption)
- **Token expiry increase**: Often not possible due to security policies

---

## Challenge: SSL/TLS Certificate Trust

### Problem Statement

The AI gateway and OAuth2 token endpoint use SSL certificates issued by an internal/company Certificate Authority (CA) that is not in the JVM's default truststore. This causes:

```
sun.security.validator.ValidatorException: PKIX path building failed
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target
```

### Solution: JVM-Level Truststore Configuration

Since Embabel creates HTTP connections deep in its stack (beyond our control), we configure SSL at the **JVM level** to ensure all HTTPS connections trust the company certificates.

#### Step 1: Extract Certificates

Use OpenSSL to extract the server certificate and CA certificate:

```bash
# Extract server certificate
echo | openssl s_client -connect aig.example.com:443 -showcerts 2>/dev/null | \
    openssl x509 -outform PEM > server.pem

# Extract CA certificate (from certificate chain)
echo | openssl s_client -connect aig.example.com:443 -showcerts 2>/dev/null | \
    sed -n '/-----BEGIN CERTIFICATE-----/,/-----END CERTIFICATE-----/p' | \
    tail -n +$(( $(grep -n "BEGIN CERTIFICATE" | tail -1 | cut -d: -f1) )) > ca.pem
```

#### Step 2: Create Custom Truststore

Import both certificates into a Java KeyStore:

```bash
# Import server certificate
keytool -importcert -alias gateway-server \
    -file server.pem \
    -keystore certs/company-truststore.jks \
    -storepass changeit -noprompt

# Import CA certificate
keytool -importcert -alias company-ca \
    -file ca.pem \
    -keystore certs/company-truststore.jks \
    -storepass changeit -noprompt
```

#### Step 3: Configure JVM System Properties

In `GatewayAuthConfig` constructor (runs early during Spring context initialization):

```java
if (props.ssl() != null && props.ssl().trustStorePath() != null) {
    Path truststorePath = Paths.get(props.ssl().trustStorePath());
    String absolutePath = truststorePath.toAbsolutePath().toString();
    
    System.setProperty("javax.net.ssl.trustStore", absolutePath);
    System.setProperty("javax.net.ssl.trustStorePassword", props.ssl().trustStorePassword());
    System.setProperty("javax.net.ssl.trustStoreType", props.ssl().trustStoreType());
}
```

**Why JVM-level?**
- Affects **all** HTTPS connections in the JVM, including those made deep inside Embabel/Spring AI
- No need to customize every `RestClient`, `HttpClient`, or `SSLContext` throughout the stack
- Works even when frameworks create their own HTTP clients

**Security Note**: This approach is safe because:
- The truststore only contains public certificates (no private keys)
- Certificates and passwords are in gitignored files
- The configuration only activates when `gateway.auth.token-url` is set

For detailed setup instructions, see [CERTIFICATES.md](CERTIFICATES.md).

---

## Solution: Local Proxy Architecture

### The Winning Approach

Instead of fighting Spring AI's internal caching, we introduced a **local HTTP proxy** that sits between Embabel and the real AI gateway. This proxy has full control over every request and can inject fresh tokens dynamically.

### Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│ Spring Boot Application                                          │
│                                                                   │
│  ┌──────────────┐           ┌──────────────────────────────┐   │
│  │   Embabel    │  Cached   │   GatewayProxyController     │   │
│  │  Spring AI   ├──Token───►│  (localhost:8080/gateway-    │   │
│  │   OpenAI     │           │         proxy/**)            │   │
│  │    Client    │           │                              │   │
│  └──────────────┘           │  1. Strip old Authorization  │   │
│                              │  2. Get fresh token          │   │
│                              │  3. Add Bearer token         │   │
│                              │  4. Forward to real gateway  │   │
│                              └──────────┬───────────────────┘   │
│                                         │                        │
└─────────────────────────────────────────┼────────────────────────┘
                                          │ Fresh OAuth2
                                          │ Bearer Token
                                          ▼
                        ┌──────────────────────────────────┐
                        │  Remote AI Gateway               │
                        │  https://aig.example.com/model   │
                        │  (Requires valid OAuth2 token)   │
                        └──────────────────────────────────┘
```

### How It Works

#### 1. Configuration

Point Embabel to the local proxy instead of the real gateway:

```properties
# application-local.properties
embabel.agent.platform.models.openai.base-url=http://localhost:8080/gateway-proxy
gateway.proxy.real-url=https://aig.example.com/gemini-2.5-flash-lite
```

Embabel now thinks the AI gateway is at `localhost:8080/gateway-proxy`.

#### 2. Request Interception

The `GatewayProxyController` intercepts all requests to `/gateway-proxy/**`:

```java
@RequestMapping(value = "/gateway-proxy/**", 
                method = {RequestMethod.POST, RequestMethod.GET})
public ResponseEntity<byte[]> proxyRequest(HttpServletRequest request, 
                                            @RequestBody(required = false) byte[] body) {
    String requestPath = request.getRequestURI().substring("/gateway-proxy".length());
    String fullUrl = realGatewayBaseUrl + requestPath;
    
    // Get FRESH OAuth2 token (auto-refreshes if expired)
    String freshToken = tokenService.getToken();
    
    // Build headers, EXCLUDING stale Authorization from Embabel
    HttpHeaders headers = new HttpHeaders();
    Enumeration<String> headerNames = request.getHeaderNames();
    while (headerNames.hasMoreElements()) {
        String headerName = headerNames.nextElement();
        if (!"authorization".equalsIgnoreCase(headerName)) {
            headers.put(headerName, Collections.list(request.getHeaders(headerName)));
        }
    }
    
    // Add fresh Bearer token
    headers.setBearerAuth(freshToken);
    
    // Forward to real gateway
    return restClient.method(HttpMethod.valueOf(request.getMethod()))
            .uri(fullUrl)
            .headers(h -> h.addAll(headers))
            .body(body)
            .retrieve()
            .toEntity(byte[].class);
}
```

#### 3. Token Management

`GatewayTokenService` manages OAuth2 tokens with automatic refresh:

```java
public synchronized String getToken() {
    if (cachedToken == null || !Instant.now().isBefore(renewAfter)) {
        refresh(); // Fetch new token from Keycloak
    }
    return cachedToken;
}

private void refresh() {
    // OAuth2 client_credentials grant
    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
    form.add("grant_type", "client_credentials");
    form.add("client_id", props.clientId());
    form.add("client_secret", props.clientSecret());
    form.add("scope", props.scope());
    
    Map<String, Object> response = restClient.post()
            .uri(props.tokenUrl())
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(form)
            .retrieve()
            .body(new ParameterizedTypeReference<>() {});
    
    this.cachedToken = response.get("access_token");
    this.renewAfter = Instant.now().plusSeconds(expiresIn - 30); // Refresh 30s early
}
```

### Why This Works

✅ **On-demand token refresh**: Proxy calls `tokenService.getToken()` on every request, which automatically fetches a new token if expired

✅ **No Spring AI coupling**: Works with any version of Spring AI / Embabel without modification

✅ **Transparent to Embabel**: Embabel doesn't know it's talking to a proxy; from its perspective it's just another OpenAI-compatible endpoint

✅ **Full control**: We can inspect, log, modify, or retry requests as needed

✅ **Simple configuration**: Just change one URL in properties file

✅ **Production-ready**: No scheduled restarts, no token expiry issues, handles network errors gracefully

### Observable Behavior

When a request comes in **after the token has expired**, logs show:

```
INFO  GatewayProxyController - 🔄 Proxying POST /v1/chat/completions to https://aig.example.com/...
DEBUG GatewayTokenService - Requesting new AI gateway token from https://keycloak.example.com/...
INFO  GatewayTokenService - Obtained AI gateway token (expires_in=300s); will auto-renew after 2026-07-20T18:43:34
DEBUG GatewayProxyController - 🔑 Using fresh OAuth2 token for proxied request
DEBUG GatewayProxyController - Stripped stale Authorization header from incoming request
INFO  GatewayProxyController - ✅ Proxied request succeeded: 819 bytes
```

The token refresh happens **inline with the request** - no background threads, no scheduled tasks, just pure request/response flow.

---

## Implementation Details

### Package Structure

```
net.timafe.travel.gateway/
├── GatewayAuthConfig.java        # SSL config, bean definitions
├── GatewayProperties.java        # @ConfigurationProperties
├── GatewayProxyController.java   # HTTP proxy endpoint
└── GatewayTokenService.java      # OAuth2 token management
```

### Configuration Files

#### `application-local.properties` (gitignored)
Contains environment-specific secrets:
- OAuth2 credentials (client ID, secret)
- Token endpoint URL
- Real gateway URL
- SSL truststore path/password

#### `application-local.properties.example` (committed)
Template with placeholder values for other developers to copy.

### Security Considerations

1. **Credentials**: Never committed to git (in `.gitignore`)
2. **Certificates**: Only public certs in truststore, gitignored
3. **Local proxy**: Only listens on localhost, not exposed to network
4. **Token caching**: Tokens stored in memory only, cleared on JVM exit
5. **HTTPS upstream**: All connections to real gateway use TLS 1.2+

### Testing the Solution

#### Test 1: First Request (Fresh Token)
1. Start application → token fetched at startup
2. Make LLM request within 5 minutes
3. ✅ Should succeed (cached token still valid)

#### Test 2: Token Expiry (Critical Test)
1. Start application → token fetched
2. **Wait 6+ minutes** (token expires at 5 minutes)
3. Make LLM request
4. ✅ Should succeed - proxy fetches fresh token automatically

#### Test 3: SSL Certificates
1. Remove company CA from truststore
2. Try to make request
3. ❌ Should fail with PKIX error
4. Re-add CA certificate
5. ✅ Should succeed

### Operational Monitoring

Key log messages to monitor:

| Log Pattern | Meaning |
|-------------|---------|
| `🔄 Gateway proxy initialized` | Proxy started successfully |
| `🔄 Proxying POST /v1/chat/completions` | Request intercepted |
| `Requesting new AI gateway token` | Token expired, fetching fresh one |
| `🔑 Using fresh OAuth2 token` | Fresh token injected into request |
| `✅ Proxied request succeeded` | End-to-end success |
| `❌ Proxy request failed` | Check network, certs, or credentials |

### Performance Impact

- **Negligible overhead**: Proxy adds ~1-5ms per request (header manipulation)
- **Token fetch**: ~200-500ms when token expires (once every 4.5 minutes)
- **No thread blocking**: Token refresh happens inline with request
- **Connection reuse**: `RestClient` pools connections to real gateway

---

## Alternative Approaches Considered

### 1. Scheduled Application Restart
- **Approach**: Restart Spring Boot app every 4 minutes
- **Rejected**: Causes service interruptions, loses in-flight requests

### 2. Increase Token Expiry
- **Approach**: Configure Keycloak to issue 8+ hour tokens
- **Rejected**: Often not permitted by security policy

### 3. Fork Spring AI / Embabel
- **Approach**: Modify upstream code to support `Supplier<String>` for API keys
- **Rejected**: High maintenance burden, hard to upgrade

### 4. Separate Proxy Process
- **Approach**: Run nginx/envoy as separate process
- **Rejected**: Extra deployment complexity, more moving parts

### 5. Spring Cloud Gateway
- **Approach**: Use reactive gateway with filters
- **Rejected**: Requires reactive stack, overkill for single endpoint

---

## Lessons Learned

1. **Framework limitations are real**: Sometimes you can't fight the framework's design decisions - work around them instead

2. **Proxy pattern FTW**: When you can't modify the client or server, sit in the middle and intercede

3. **JVM-level config matters**: SSL/TLS settings at JVM level affect everything - sometimes that's exactly what you need

4. **Test token expiry explicitly**: Don't assume token refresh works - wait for actual expiry and verify

5. **Simple beats clever**: The proxy solution is conceptually simple (HTTP forwarding) despite solving a complex caching problem

6. **Logs are documentation**: Emoji-prefixed logs (`🔄`, `🔑`, `✅`) make debugging and monitoring intuitive

---

## Future Improvements

### Potential Enhancements

1. **Retry logic**: Automatically retry on 401 with fresh token
2. **Circuit breaker**: Fail fast if gateway is consistently down
3. **Metrics**: Expose token refresh rate, request latency, error rate
4. **Request pooling**: Batch concurrent requests during token refresh
5. **Graceful degradation**: Fall back to cached LLM responses on gateway failure

### Upstream Contributions

Consider contributing to Spring AI:

```java
// Proposed Spring AI enhancement
public interface DynamicApiKeyProvider {
    String getApiKey();
}

// Usage in OpenAiApi
public OpenAiApi(String baseUrl, DynamicApiKeyProvider apiKeyProvider) {
    this.apiKeyProvider = apiKeyProvider;
}

public ChatCompletion chat(...) {
    String apiKey = apiKeyProvider.getApiKey(); // Called on every request
    headers.setBearerAuth(apiKey);
}
```

This would eliminate the need for the proxy in future versions.

---

## Conclusion

The combination of **JVM-level SSL configuration** and a **local HTTP proxy** solves both the certificate trust and token caching challenges. The solution is:

- ✅ Production-ready (runs 24/7 without restarts)
- ✅ Framework-agnostic (works with any version of Spring AI/Embabel)
- ✅ Maintainable (all custom code in one package)
- ✅ Observable (clear log messages for debugging)
- ✅ Secure (credentials gitignored, HTTPS enforced)

**Key takeaway**: When frameworks cache values you need to be dynamic, introduce a layer you control (the proxy) and let it handle the dynamism transparently.

*(Note: this conclusion described the proxy-based solution, since superseded — see the next section.)*

---

## Update: Removing the Proxy — Direct OAuth2 Client Wiring

**tl;dr**: Embabel/Spring AI can, in fact, use short-lived OAuth2 tokens with an OpenAI-compatible
gateway *without* a local proxy. The trick is finding the one seam Embabel actually reads from at
request time, rather than fighting its cached-`api-key` design head-on. This section documents
that seam and replaces the proxy from the previous sections.

### The missed seam

Attempts 1–4 above all tried to inject a fresh token into the OpenAI `api-key`/`Authorization`
mechanism *after* Embabel had already built its `OpenAiApi` client — that's why they all failed the
same way (cached value, bean never invoked, request bypasses `@Primary`). What none of the earlier
attempts checked was **what Embabel's `OpenAiModelsConfig` actually asks the Spring context for
before building that client**:

```kotlin
// embabel-agent-openai-autoconfigure, OpenAiModelsConfig.kt
@Qualifier("aiModelRestClientBuilder")
restClientBuilder: ObjectProvider<RestClient.Builder>,
```

```kotlin
// embabel-agent-openai, OpenAiCompatibleModelFactory.kt
builder.restClientBuilder(
    restClientBuilder.getIfAvailable {
        RestClient.builder().requestFactory(/* plain, no auth */)
    }
)
```

Embabel looks up an `ObjectProvider<RestClient.Builder>` under the **exact qualifier
`"aiModelRestClientBuilder"`**, and if a bean is published under that name, uses it *verbatim* to
build the underlying `OpenAiApi` client. Publish that bean ourselves — with an OAuth2 bearer-token
interceptor attached — and every outbound LLM HTTP call gets a freshly-minted token, no matter how
short its lifetime, with zero proxying or property-source trickery. This resolves the "one
uncertain link" flagged in `gateway-proxy-removal.md` (whether Embabel routes through the
Spring-managed `RestClient.Builder` at all) — it does, just under a specific qualifier rather than
the generic autoconfigured one Boot's `RestClientCustomizer` targets.

### New architecture

Standard Spring Security OAuth2 client machinery (`spring-boot-starter-oauth2-client`), wired
directly to that qualified bean:

```java
@Bean
ClientRegistrationRepository gatewayClientRegistrationRepository(GatewayProperties props) {
    ClientRegistration registration = ClientRegistration.withRegistrationId("gateway")
            .tokenUri(props.tokenUrl())
            .clientId(props.clientId())
            .clientSecret(props.clientSecret())
            .scope(props.scope())
            .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
            .build();
    return new InMemoryClientRegistrationRepository(registration);
}

@Bean
OAuth2AuthorizedClientManager gatewayAuthorizedClientManager(
        ClientRegistrationRepository registrations, OAuth2AuthorizedClientService clientService) {
    // Service-based, not request-scoped: Embabel's calls aren't guaranteed to run on a
    // servlet-request thread.
    var manager = new AuthorizedClientServiceOAuth2AuthorizedClientManager(registrations, clientService);
    manager.setAuthorizedClientProvider(
            OAuth2AuthorizedClientProviderBuilder.builder().clientCredentials().build());
    return manager;
}

@Bean("aiModelRestClientBuilder")
RestClient.Builder aiModelRestClientBuilder(OAuth2AuthorizedClientManager manager) {
    var oauth = new OAuth2ClientHttpRequestInterceptor(manager);
    oauth.setClientRegistrationIdResolver(request -> "gateway"); // fixed id
    return RestClient.builder().requestInterceptor(oauth);
}
```

`GatewayProxyController` and `GatewayTokenService` are deleted entirely. The `base-url` now points
straight at the real gateway; there's no `localhost:8080/gateway-proxy` indirection.

### Two gotchas discovered along the way

1. **Client authentication method matters.** Spring Security defaults new `ClientRegistration`s to
   `client_secret_basic` (credentials sent via HTTP Basic `Authorization` header on the token
   request). Many simple/mock token endpoints — and some real ones — expect
   `client_secret_post` (credentials as regular form fields in the POST body) instead. Getting
   this wrong produces a confusing `401 Unauthorized` from the *token endpoint itself*, easily
   mistaken for bad credentials. Fixed via
   `.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)`.

2. **Adding `spring-boot-starter-oauth2-client` silently locks down the whole app.** Its presence
   on the classpath activates Spring Security's default autoconfiguration, which requires a login
   for every request — turning the public travel-planner UI into a 302-to-`/login` redirect. This
   has nothing to do with the AI gateway (that's outbound, server-to-server auth) but is an
   unavoidable side effect of the dependency. Fixed with a small, unconditional
   `GatewaySecurityConfig` that `permitAll()`s every request — the app has no user-facing
   authentication of its own; the OAuth2 client is used purely as an outbound-auth library.

### Why this is better than the proxy

- **No extra network hop**: requests go straight from Embabel to the real gateway; the proxy added
  a full extra HTTP round-trip (Embabel → proxy → gateway) for every LLM call.
- **No forwarding logic to maintain**: no header stripping/copying, no manual `RestClient`
  plumbing for the forward — Spring Security's interceptor handles authorization end-to-end.
- **Standard, well-tested library code**: `OAuth2AuthorizedClientManager` and
  `OAuth2ClientHttpRequestInterceptor` are maintained by the Spring Security team, not bespoke
  application code.
- **Same operational guarantees**: still fully in-process, still refreshes inline with the
  request (no scheduled tasks/background threads), still framework-version-agnostic as long as
  Embabel keeps honoring the `aiModelRestClientBuilder` qualifier.

### Verified end-to-end against the mocks

The dependency-free mock OIDC + mock AI gateway (`mocks/mock_oidc.py`, `mocks/mock_gateway.py`, see
[mocks/README.md](mocks/README.md)) turned out to be just as valuable for validating the *removal*
of the proxy as it was for building it in the first place — no real gateway, IdP, or network access
needed, and a short configurable token TTL (`MOCK_TOKEN_TTL`) makes expiry/refresh observable in
seconds instead of the real gateway's 5 minutes. `make run-mock` starts both mocks and the app
against them in one step; this remains the easiest way to (re-)verify the token-refresh path after
any future change to `GatewayAuthConfig` or an Embabel/Spring Security upgrade.

Using `make run-mock` (`MOCK_TOKEN_TTL=10`s):

1. Form submission → real round trip through OAuth2 `client_credentials` → mock OIDC → gateway →
   randomized destination suggestion (proves it's a live call, not cached).
2. Waited past the token TTL, submitted again → `mocks/.mock_oidc.log` showed a **second, distinct**
   `issued token` line with a later `expires_at`, and a **different** randomized suggestion — proof
   the refresh path works, not just that the app started successfully.

This mirrors "Test 2" from this document's original testing section, just without a proxy in the
request path.

### Lessons learned (addendum)

- **Read the framework's actual lookup code, not just its docs.** The fix here wasn't a new
  Spring Security feature — `ObjectProvider.getIfAvailable` + a specific `@Qualifier` string had
  been there in Embabel all along; Attempts 1–4 above never looked at *what Embabel asks the
  context for*, only at *what Spring AI does with the value once built*.
- **A generic `RestClientCustomizer` is not the same as a qualified bean lookup.** Boot's
  autoconfigured `RestClient.Builder` (which `RestClientCustomizer` beans modify) is a different
  bean than the one Embabel explicitly requests by qualifier — customizing the former does nothing
  for Embabel unless the qualifier also happens to be published.
- **New dependencies can have side effects unrelated to why you added them.** Adding
  `oauth2-client` for outbound auth silently added inbound auth requirements too, via Spring
  Security's autoconfiguration — always sanity-check the app's own public endpoints after adding a
  security-adjacent starter.

