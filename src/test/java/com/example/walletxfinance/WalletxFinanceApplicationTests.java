package com.example.walletxfinance;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.reactive.function.client.WebClient;
import static org.junit.jupiter.api.Assertions.*;

@org.springframework.context.annotation.Import(FinanceSchemaTestSupport.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "walletx.introspection.client-secret=integration-test-service-secret-at-least-32-characters",
        "walletx.introspection.read-timeout=1s"})
class WalletxFinanceApplicationTests {
    private static final String SCHEMA="finance_test_"+java.util.UUID.randomUUID().toString().replace("-","");
    private static final String USER_ID="b6471184-51a4-4613-9f68-a0c02122cb29";
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final ExecutorService EXECUTOR=Executors.newCachedThreadPool();
    private static final HttpServer AUTH = startAuthStub();
    @LocalServerPort int port;
    @Autowired DataSource dataSource;
    @Autowired WebApplicationContext applicationContext;
    @Autowired WebClient.Builder webClientBuilder;

    @DynamicPropertySource
    static void authEndpoint(DynamicPropertyRegistry registry) {
        registry.add("test.finance-schema",() -> SCHEMA);
        registry.add("spring.flyway.schemas",() -> SCHEMA);
        registry.add("spring.flyway.placeholders.financeSchema",() -> SCHEMA);
        registry.add("walletx.introspection.uri", () -> "http://localhost:"+AUTH.getAddress().getPort()+"/introspect");
    }

    private static HttpServer startAuthStub() {
        try {
            var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.setExecutor(EXECUTOR);
            server.createContext("/introspect", exchange -> {
                REQUESTS.incrementAndGet();
                String expected="Basic "+Base64.getEncoder().encodeToString(
                        "walletx-finance:integration-test-service-secret-at-least-32-characters".getBytes(StandardCharsets.UTF_8));
                if(!expected.equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(401,-1); exchange.close(); return;
                }
                String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
                String token=URLDecoder.decode(body.substring("token=".length()),StandardCharsets.UTF_8);
                int status=200; String result;
                switch(token) {
                    case "user-token", "admin-token" -> result="{\"active\":true,\"sub\":\""+USER_ID+"\",\"iss\":\"walletx-auth\",\"aud\":[\"walletx-api\"],\"exp\":"+
                            Instant.now().plusSeconds(600).getEpochSecond()+",\"roles\":[\""+(token.equals("admin-token")?"ADMIN":"USER")+"\"]}";
                    case "outage-token" -> { status=503; result="{}"; }
                    case "timeout-token" -> {
                        try { Thread.sleep(2500); } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                        result="{\"active\":false}";
                    }
                    case "bad-claims-token" -> result="{\"active\":true,\"sub\":\"invalid-user\",\"roles\":[\"ADMIN\"]}";
                    default -> result="{\"active\":false}";
                }
                byte[] bytes=result.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                try { exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch(java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
    }

    @AfterAll static void stopAuthStub(@Autowired DataSource dataSource) { AUTH.stop(0); EXECUTOR.shutdownNow(); FinanceSchemaTestSupport.cleanup(dataSource,SCHEMA); }

    private HttpResponse<String> get(String path,String token) throws Exception {
        var request=HttpRequest.newBuilder(URI.create("http://localhost:"+port+path)).GET();
        if(token!=null) request.header("Authorization","Bearer "+token);
        return HttpClient.newHttpClient().send(request.build(),HttpResponse.BodyHandlers.ofString());
    }

    @Test void startsOnServletStackWithPostgresAndWebClient() throws Exception {
        assertNotNull(applicationContext.getServletContext()); assertNotNull(webClientBuilder.build());
        try(var connection=dataSource.getConnection(); var statement=connection.createStatement()) {
            assertEquals("PostgreSQL",connection.getMetaData().getDatabaseProductName());
            try(var result=statement.executeQuery("SELECT 1")) { assertTrue(result.next()); assertEquals(1,result.getInt(1)); }
        }
        int before=REQUESTS.get();
        var health=get("/actuator/health",null); assertEquals(200,health.statusCode());
        assertTrue(health.body().contains("\"status\":\"UP\"")); assertEquals(before,REQUESTS.get());
    }

    @Test void requiresBearerTokenAndMapsValidatedIdentityAndRoles() throws Exception {
        assertEquals(401,get("/api/v1/finance/me",null).statusCode());
        var profile=get("/api/v1/finance/me","user-token"); assertEquals(200,profile.statusCode());
        assertTrue(profile.body().contains(USER_ID)); assertTrue(profile.body().contains("USER"));
        assertTrue(profile.headers().firstValue("Set-Cookie").isEmpty());
        assertEquals(403,get("/api/v1/finance/admin/status","user-token").statusCode());
        assertEquals(200,get("/api/v1/finance/admin/status","admin-token").statusCode());
        int before=REQUESTS.get();
        assertEquals(200,get("/api/v1/finance/me","user-token").statusCode());
        assertEquals(200,get("/api/v1/finance/me","user-token").statusCode());
        assertEquals(before+2,REQUESTS.get()); // No cached authorization bypass after logout.
    }

    @Test void rejectsInactiveAndMalformedTokensAndFailsClosedOnAuthOutages() throws Exception {
        assertEquals(401,get("/api/v1/finance/me","revoked-token").statusCode());
        assertEquals(401,get("/api/v1/finance/me","expired-token").statusCode());
        assertEquals(401,get("/api/v1/finance/me","bad-claims-token").statusCode());
        assertEquals(503,get("/api/v1/finance/me","outage-token").statusCode());
        assertEquals(503,get("/api/v1/finance/me","timeout-token").statusCode());
    }
}
