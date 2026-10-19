package com.example.walletxfinance.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        var bearerEntryPoint = new BearerTokenAuthenticationEntryPoint();
        org.springframework.security.web.AuthenticationEntryPoint entryPoint = (request, response, exception) -> {
            if (exception instanceof AuthenticationServiceException) {
                // Outage/misconfigured service credentials must never grant access.
                response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
                response.setHeader("Cache-Control", "no-store");
            } else {
                bearerEntryPoint.commence(request, response, exception);
            }
        };
        return http.csrf(csrf -> csrf.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/finance/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint(entryPoint))
                .oauth2ResourceServer(resource -> resource
                        .authenticationEntryPoint(entryPoint)
                        .opaqueToken(org.springframework.security.config.Customizer.withDefaults())
                        .withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                            @Override
                            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                                // Handle service exceptions here instead of rethrowing to an
                                // error dispatch that would hide an auth outage as a 401.
                                filter.setAuthenticationFailureHandler((request, response, exception) ->
                                        entryPoint.commence(request, response, exception));
                                return filter;
                            }
                        }))
                .build();
    }
}
