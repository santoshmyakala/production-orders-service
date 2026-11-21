package io.github.santoshmyakala.orders.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * OAuth2 resource server: callers present a JWT from the company identity provider (Entra ID,
 * Keycloak…). Roles come from the {@code roles} claim:
 * <ul>
 *   <li>{@code planner}: plans, releases and cancels orders</li>
 *   <li>{@code operator}: starts and completes orders on the shop floor</li>
 *   <li>{@code viewer}: read only</li>
 * </ul>
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                        // probes and metrics; in Kubernetes they are reachable only inside the cluster
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/orders/**").hasAnyRole("viewer", "planner", "operator")
                        .requestMatchers(HttpMethod.POST, "/api/orders").hasRole("planner")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/release", "/api/orders/*/cancel").hasRole("planner")
                        .requestMatchers(HttpMethod.POST, "/api/orders/*/start", "/api/orders/*/complete").hasRole("operator")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(rs -> rs.jwt(jwt -> jwt.jwtAuthenticationConverter(roles())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()) // stateless bearer-token API: no cookies, so no CSRF
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable())
                .headers(Customizer.withDefaults());
        return http.build();
    }

    /** Maps the {@code roles} claim to {@code ROLE_*} authorities. Public so tests use the real mapping. */
    public static JwtGrantedAuthoritiesConverter rolesClaim() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName("roles");
        authorities.setAuthorityPrefix("ROLE_");
        return authorities;
    }

    private static JwtAuthenticationConverter roles() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(rolesClaim());
        return converter;
    }
}
