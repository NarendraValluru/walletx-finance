package com.example.walletxfinance;

import com.example.walletxfinance.config.SetuProperties;
import com.example.walletxfinance.exception.BankIntegrationException;
import com.example.walletxfinance.integration.SetuClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class SetuClientResponseTests {
    @Test void rejectsEmptyResponseWithoutLeakingProviderData() throws Exception { check("", "Setu returned an empty response"); }
    @Test void rejectsMalformedResponseWithoutLeakingProviderData() throws Exception { check("<html>private-provider-content</html>", "Setu returned invalid JSON"); }
    @Test void rejectsUnexpectedResponseShape() throws Exception { check("[]", "Setu returned an unexpected response format"); }

    private void check(String body,String expected) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,bytes.length==0?-1:bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            var properties=new SetuProperties();
            properties.setEnabled(true);
            var uri=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            properties.setBaseUrl(uri);properties.setTokenUrl(uri);
            var client=new SetuClient(properties,WebClient.builder());
            var error=assertThrows(BankIntegrationException.class,() -> client.consent("existing-consent"));
            assertEquals(502,error.getStatus());assertEquals(expected,error.getMessage());
        } finally { server.stop(0); }
    }
}
