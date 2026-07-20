package net.timafe.travel.gateway;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;

/**
 * Local HTTP proxy that intercepts OpenAI API calls and injects fresh OAuth2 tokens.
 * 
 * <p>This proxy solves the Spring AI token caching problem by sitting between Embabel
 * and the actual AI gateway. Embabel is configured to talk to this local endpoint
 * (e.g., http://localhost:8080/gateway-proxy) instead of the real gateway. This proxy:
 * <ol>
 *   <li>Receives the request from Embabel (with stale cached token)</li>
 *   <li>Strips the old Authorization header</li>
 *   <li>Adds a fresh OAuth2 Bearer token from GatewayTokenService</li>
 *   <li>Forwards the request to the real gateway</li>
 *   <li>Returns the response back to Embabel</li>
 * </ol>
 * 
 * <p>This approach gives us full control over every request without fighting Spring AI's
 * internal caching mechanisms.
 */
@RestController
@ConditionalOnProperty(name = "gateway.auth.token-url")
public class GatewayProxyController {
    
    private static final Logger log = LoggerFactory.getLogger(GatewayProxyController.class);
    
    private final GatewayTokenService tokenService;
    private final GatewayProperties props;
    private final RestClient restClient;
    private final String realGatewayBaseUrl;
    
    public GatewayProxyController(GatewayTokenService tokenService, 
                                   GatewayProperties props,
                                   @org.springframework.beans.factory.annotation.Value("${gateway.proxy.real-url}") String realGatewayUrl) {
        this.tokenService = tokenService;
        this.props = props;
        this.realGatewayBaseUrl = realGatewayUrl;
        
        // Create RestClient for proxying - uses JVM-level SSL config
        this.restClient = RestClient.builder().build();
        
        log.info("🔄 Gateway proxy initialized - will forward requests from /gateway-proxy/** to {}", realGatewayBaseUrl);
        log.info("🔄 Embabel is configured to use: http://localhost:8080/gateway-proxy");
    }
    
    /**
     * Proxies all requests to /gateway-proxy/** to the real gateway with fresh OAuth2 tokens.
     */
    @RequestMapping(value = "/gateway-proxy/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH})
    public ResponseEntity<byte[]> proxyRequest(HttpServletRequest request, @RequestBody(required = false) byte[] body) throws IOException {
        
        // Extract the path after /gateway-proxy
        String requestPath = request.getRequestURI().substring("/gateway-proxy".length());
        String fullUrl = realGatewayBaseUrl + requestPath;
        
        if (request.getQueryString() != null) {
            fullUrl += "?" + request.getQueryString();
        }
        
        log.info("🔄 Proxying {} {} to {}", request.getMethod(), requestPath, fullUrl);
        
        // Get fresh OAuth2 token
        String freshToken = tokenService.getToken();
        log.debug("🔑 Using fresh OAuth2 token for proxied request");
        
        // Build headers, excluding Authorization from original request
        HttpHeaders headers = new HttpHeaders();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            // Skip Authorization header - we'll add our own
            if ("authorization".equalsIgnoreCase(headerName)) {
                log.debug("Stripped stale Authorization header from incoming request");
                continue;
            }
            // Skip Host header - RestClient will set it
            if ("host".equalsIgnoreCase(headerName)) {
                continue;
            }
            headers.put(headerName, Collections.list(request.getHeaders(headerName)));
        }
        
        // Add fresh Bearer token
        headers.setBearerAuth(freshToken);
        
        // Forward request to real gateway
        try {
            ResponseEntity<byte[]> response = restClient.method(HttpMethod.valueOf(request.getMethod()))
                    .uri(fullUrl)
                    .headers(h -> h.addAll(headers))
                    .body(body != null ? body : new byte[0])
                    .retrieve()
                    .toEntity(byte[].class);
            
            log.info("✅ Proxied request succeeded: {} bytes", response.getBody() != null ? response.getBody().length : 0);
            
            return response;
            
        } catch (Exception e) {
            log.error("❌ Proxy request failed: {}", e.getMessage(), e);
            throw e;
        }
    }
}
