package com.example.walletxfinance.config;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DefaultOAuth2AuthenticatedPrincipal;
import org.springframework.security.oauth2.server.resource.introspection.*;
import org.springframework.web.client.RestClient;

@Configuration
public class IntrospectionConfig {
    @Bean
    OpaqueTokenIntrospector opaqueTokenIntrospector(
            @Value("${walletx.introspection.uri}") String uri,
            @Value("${walletx.introspection.client-id}") String clientId,
            @Value("${walletx.introspection.client-secret}") String clientSecret,
            @Value("${walletx.introspection.connect-timeout}") Duration connectTimeout,
            @Value("${walletx.introspection.read-timeout}") Duration readTimeout) {
        if (clientId.isBlank() || clientSecret.length() < 32) {
            throw new IllegalArgumentException("Configure private introspection credentials; run configure-auth-link.ps1 locally");
        }
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        var client = RestClient.builder().requestFactory(requestFactory)
                .defaultHeaders(headers -> headers.setBasicAuth(clientId, clientSecret)).build();
        var delegate = new RestClientOpaqueTokenIntrospector(uri, client);
        return token -> {
            var principal = delegate.introspect(token);
            // Do not trust a malformed active response, even from the configured service.
            try {
                UUID.fromString(principal.getAttribute("sub"));
                List<String> roles = principal.getAttribute("roles");
                if (roles == null || roles.isEmpty() || roles.stream().anyMatch(role -> !List.of("USER", "ADMIN").contains(role))) {
                    throw new IllegalArgumentException("Unexpected roles");
                }
                return new DefaultOAuth2AuthenticatedPrincipal(principal.getName(), principal.getAttributes(),
                        roles.stream().<org.springframework.security.core.GrantedAuthority>map(role -> new SimpleGrantedAuthority("ROLE_" + role)).toList());
            } catch (RuntimeException malformedResponse) {
                throw new BadOpaqueTokenException("Invalid introspection claims");
            }
        };
    }
}
