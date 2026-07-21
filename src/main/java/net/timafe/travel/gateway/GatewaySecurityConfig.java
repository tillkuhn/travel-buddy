package net.timafe.travel.gateway;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Pulling in {@code spring-boot-starter-oauth2-client} (needed for the {@code
 * OAuth2AuthorizedClientManager} used to authenticate outbound calls to the AI gateway, see
 * {@link GatewayAuthConfig}) transitively activates Spring Security's autoconfiguration, which by
 * default requires a login for every request.
 *
 * <p>This app has no user-facing authentication of its own — the OAuth2 client is used purely as
 * a library to mint outbound bearer tokens, not to secure inbound HTTP requests. This
 * configuration disables that default "secure everything" behaviour so the travel-planner UI
 * remains publicly accessible, same as before this dependency was added.
 */
@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfig {

    @Bean
    SecurityFilterChain permitAllFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .csrf(csrf -> csrf.disable());
        return http.build();
    }
}
