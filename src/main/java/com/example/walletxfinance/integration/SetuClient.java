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
    public JsonNode consent(String id) { return request(HttpMethod.GET,"/v2/consents/"+identifier(id),null); }
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
        if(value.isBlank() || ttl<1) throw new BankIntegrationException(502,"Setu authentication response is incomplete");
        accessToken=value; tokenExpires=Instant.now().plusSeconds(Math.max(0,ttl-30)); return value;
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
            return raw==null || raw.isBlank()?json.createObjectNode():json.readTree(raw);
        } catch(WebClientResponseException upstream) {
            if(upstream.getStatusCode().value()==401) { synchronized(this) { accessToken=null; tokenExpires=Instant.EPOCH; } }
            // Do not expose upstream bodies, credentials, or bank data in errors/logs.
            throw new BankIntegrationException(upstream.getStatusCode().value()==429?429:502,
                    "Setu request failed (HTTP "+upstream.getStatusCode().value()+"); check sandbox configuration or retry a safe status request");
        } catch(BankIntegrationException known) { throw known; }
        catch(RuntimeException unavailable) { throw new BankIntegrationException(502,"Setu did not return a usable response; check provider status before repeating a create request"); }
    }
}
