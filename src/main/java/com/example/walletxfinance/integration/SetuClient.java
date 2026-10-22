package com.example.walletxfinance.integration;

import com.example.walletxfinance.config.SetuProperties;
import com.example.walletxfinance.exception.BankIntegrationException;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.*;
import org.springframework.http.*;
import org.springframework.http.client.reactive.JdkClientHttpConnector;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.time.*;
import java.util.*;

@Component
public class SetuClient {
    private final SetuProperties properties;
    private final WebClient client;
    private final JsonMapper json=JsonMapper.builder().build();
    private String accessToken;
    private Instant tokenExpires=Instant.EPOCH;
    public SetuClient(SetuProperties properties, WebClient.Builder builder) {
        this.properties=properties;
        this.client=builder.clone().clientConnector(new JdkClientHttpConnector(java.net.http.HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build()))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(8*1024*1024)).build();
    }
    public JsonNode consent(String id) { return request(HttpMethod.GET,"/v2/consents/"+identifier(id)+"?expanded=true",null); }
    public JsonNode createConsent(Object payload) { return request(HttpMethod.POST,"/v2/consents",payload); }
    public JsonNode revoke(String id) { return request(HttpMethod.POST,"/v2/consents/"+identifier(id)+"/revoke",null); }
    public JsonNode createSession(Object payload) { return request(HttpMethod.POST,"/v2/sessions",payload); }
    public JsonNode session(String id) { return request(HttpMethod.GET,"/v2/sessions/"+identifier(id),null); }
    private String identifier(String value) {
        if(value==null || !value.matches("[A-Za-z0-9_-]{1,255}")) throw new BankIntegrationException(502,"Provider returned an invalid reference");
        return value;
    }
    private void enabled() {
        if(!properties.isEnabled()) throw new BankIntegrationException(503,"Setu integration is disabled; configure sandbox credentials locally first");
    }
    private synchronized String token() {
        enabled();
        if(accessToken!=null && Instant.now().isBefore(tokenExpires)) return accessToken;
        JsonNode response=send(HttpMethod.POST,properties.getTokenUrl().toString(),Map.of(
                "clientID",properties.getClientId(),"secret",properties.getClientSecret(),"grant_type","client_credentials"),null,true);
        String value=response.path("access_token").asString("");
        long ttl=response.path("expires_in").asLong(0);
        if(value.isBlank()) throw new BankIntegrationException(502,"Setu authentication response has no access token");
        // Bridge may return access_token and refresh_token without expires_in.
        // Such tokens can be used for this request, but must not be cached with a guessed lifetime.
        accessToken=value;
        tokenExpires=ttl>30 && ttl<=86400 ? Instant.now().plusSeconds(ttl-30) : Instant.EPOCH;
        return value;
    }
    private JsonNode request(HttpMethod method,String path,Object body) {
        String base=properties.getBaseUrl().toString().replaceAll("/$","");
        // No automatic retry for provider POSTs: a timeout may have created a resource.
        return send(method,base+path,body,token(),false);
    }
    private JsonNode send(HttpMethod method,String uri,Object body,String bearer,boolean login) {
        try {
            var request=client.method(method).uri(uri).contentType(MediaType.APPLICATION_JSON).accept(MediaType.APPLICATION_JSON);
            if(login) request.header("client","bridge");
            else request.headers(h -> { h.setBearerAuth(bearer); h.set("x-product-instance-id",properties.getProductId()); });
            WebClient.RequestHeadersSpec<?> spec=body==null?request:request.bodyValue(body);
            String raw=spec.retrieve().bodyToMono(String.class).block(Duration.ofSeconds(20));
            if(raw==null || raw.isBlank()) throw new BankIntegrationException(502,"Setu returned an empty response");
            JsonNode parsed;
            try { parsed=json.readTree(raw); }
            catch(RuntimeException invalid) { throw new BankIntegrationException(502,"Setu returned invalid JSON"); }
            if(!parsed.isObject()) throw new BankIntegrationException(502,"Setu returned an unexpected response format");
            return parsed;
        } catch(WebClientResponseException upstream) {
            if(upstream.getStatusCode().value()==401) { synchronized(this) { accessToken=null; tokenExpires=Instant.EPOCH; } }
            // Do not expose upstream bodies, credentials, or bank data in errors/logs.
            throw new BankIntegrationException(upstream.getStatusCode().value()==429?429:502,
                    "Setu request failed (HTTP "+upstream.getStatusCode().value()+"); check sandbox configuration or retry a safe status request", upstream.getStatusCode().value());
        } catch(BankIntegrationException known) { throw known; }
        catch(RuntimeException unavailable) {
            for(Throwable cause=unavailable;cause!=null;cause=cause.getCause()) {
                if(cause instanceof java.net.UnknownHostException || cause instanceof java.nio.channels.UnresolvedAddressException)
                    throw new BankIntegrationException(502,"Cannot resolve the Setu host; check DNS, internet connectivity and the configured Setu URLs");
                if(cause instanceof javax.net.ssl.SSLException)
                    throw new BankIntegrationException(502,"TLS connection to Setu failed; check the Java trust store and any proxy or VPN");
                if(cause instanceof java.util.concurrent.TimeoutException || cause instanceof java.net.http.HttpTimeoutException)
                    throw new BankIntegrationException(504,"Setu request timed out; refresh the existing resource before repeating a create request");
                if(cause instanceof java.net.ConnectException)
                    throw new BankIntegrationException(502,"Cannot connect to Setu; check internet connectivity, firewall and provider availability");
            }
            throw new BankIntegrationException(502,"Setu response could not be processed; check provider availability before repeating a create request");
        }
    }
}
