package com.example.walletxfinance;

import com.sun.net.httpserver.HttpServer;
import com.example.walletxfinance.repository.*;
import com.example.walletxfinance.service.SetuSyncWorker;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

@Import(FinanceSchemaTestSupport.class)
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
 "walletx.introspection.client-secret=integration-test-service-secret-at-least-32-characters",
 "walletx.setu.enabled=true","walletx.setu.allow-local-http=true","walletx.setu.polling-enabled=false",
 "walletx.setu.client-id=mock-client","walletx.setu.client-secret=mock-secret","walletx.setu.product-id=mock-product",
 "walletx.setu.callback-token=callback-test-token-with-at-least-forty-characters"})
class SetuIntegrationTests {
 static final String SCHEMA="finance_test_"+UUID.randomUUID().toString().replace("-","");
 static final String CALLBACK="/api/v1/integrations/setu/webhook/callback-test-token-with-at-least-forty-characters";
 static final String FROM="2024-01-01T00:00:00Z",TO="2024-02-01T00:00:00Z";
 static final JsonMapper JSON=JsonMapper.builder().build();
 static final Map<String,String> CONSENTS=new ConcurrentHashMap<>(),SESSIONS=new ConcurrentHashMap<>();
 static final Set<String> REJECT_CREATES=ConcurrentHashMap.newKeySet(),FAILED_SESSIONS=ConcurrentHashMap.newKeySet();
 static final AtomicInteger TOKEN_CALLS=new AtomicInteger(),CREATE_CALLS=new AtomicInteger(),SESSION_CALLS=new AtomicInteger();
 static final ExecutorService EXECUTOR=Executors.newVirtualThreadPerTaskExecutor();
 static final HttpServer STUB=startStub();
 @LocalServerPort int port;
 @Autowired BankConsentRepository consents;
 @Autowired SetuWebhookEventRepository events;
 @Autowired SetuSyncWorker worker;
 @Autowired JdbcTemplate jdbc;
 @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) {
  String base="http://127.0.0.1:"+STUB.getAddress().getPort();
  registry.add("walletx.introspection.uri",() -> base+"/introspect");
  registry.add("walletx.setu.base-url",() -> base);registry.add("walletx.setu.token-url",() -> base+"/token");
  registry.add("test.finance-schema",() -> SCHEMA);registry.add("spring.flyway.schemas",() -> SCHEMA);
  registry.add("spring.flyway.placeholders.financeSchema",() -> SCHEMA);
 }
 @AfterAll static void cleanup(@Autowired javax.sql.DataSource dataSource) {
  STUB.stop(0);EXECUTOR.shutdownNow();FinanceSchemaTestSupport.cleanup(dataSource,SCHEMA);
 }
 static Map<String,Object> detail(String id) {
  return Map.of("consentExpiry",Instant.now().plusSeconds(3600).toString(),"dataLife",Map.of("unit","DAY","value",1),
   "dataRange",Map.of("from",FROM,"to",TO));
 }
 static HttpServer startStub() {
  try {
   var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(EXECUTOR);
   server.createContext("/",x -> {
    int code=200; Object response=Map.of();String path=x.getRequestURI().getPath(),method=x.getRequestMethod();
    String body=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
    try {
     if(path.equals("/introspect")) {
      String token=URLDecoder.decode(body.substring(6),StandardCharsets.UTF_8);
      response=token.startsWith("user-")?Map.of("active",true,"sub",token.substring(5),"roles",List.of("USER"),"exp",Instant.now().plusSeconds(600).getEpochSecond()):Map.of("active",false);
     } else if(path.equals("/token")) {
      var input=JSON.readTree(body);
      if(!"mock-client".equals(input.path("clientID").asString()) || !"mock-secret".equals(input.path("secret").asString()) || !"bridge".equals(x.getRequestHeaders().getFirst("client"))) code=401;
      else {TOKEN_CALLS.incrementAndGet();response=Map.of("access_token","provider-token","refresh_token","mock-refresh-token");}
     } else if(!"Bearer provider-token".equals(x.getRequestHeaders().getFirst("Authorization")) || !"mock-product".equals(x.getRequestHeaders().getFirst("x-product-instance-id"))) code=401;
     else if(path.equals("/v2/consents") && method.equals("POST")) {
      CREATE_CALLS.incrementAndGet();String id=UUID.randomUUID().toString();CONSENTS.put(id,"PENDING");
      response=Map.of("id",id,"status","PENDING","url","https://example.test/consent/"+id,"detail",detail(id));code=201;
     } else if(path.startsWith("/v2/consents/")) {
      String id=path.split("/")[3];
      if(!CONSENTS.containsKey(id)) code=404;
      else if(path.endsWith("/revoke")) {CONSENTS.put(id,"REVOKED");response=Map.of();}
      else if("ERROR".equals(CONSENTS.get(id))) {code=503;response=Map.of("secret","do-not-expose-provider-body");}
      else response=Map.of("id",id,"status",CONSENTS.get(id),"detail",detail(id),"accountsLinked",List.of(Map.of("fipId","mock-bank","linkRefNumber","account-"+id)));
     } else if(path.equals("/v2/sessions") && method.equals("POST")) {
      SESSION_CALLS.incrementAndGet();String id=UUID.randomUUID().toString(),consent=JSON.readTree(body).path("consentId").asString();
      if(REJECT_CREATES.contains(consent)) {code=400;response=Map.of("errorMsg","private upstream detail");}
      else {SESSIONS.put(id,consent);response=Map.of("id",id,"status","PENDING");code=201;}
     } else if(path.startsWith("/v2/sessions/")) {
      String consent=SESSIONS.get(path.split("/")[3]);
      if(consent==null) code=404;
      else {
       var transaction=Map.of("txnId","sample-txn-1","amount","25.50","type","DEBIT","transactionTimestamp","2024-01-10T12:00:00Z","narration","Sample restaurant","mode","UPI");
       var account=Map.of("type","deposit","summary",Map.of("currency","INR","type","SAVINGS"),"transactions",Map.of("transaction",List.of(transaction)));
       var item=Map.of("linkRefNumber","account-"+consent,"maskedAccNumber","XXXX1234","FIstatus","DELIVERED","data",Map.of("account",account));
       response=Map.of("status",FAILED_SESSIONS.contains(consent)?"FAILED":"COMPLETED","consentId",consent,"fips",List.of(Map.of("fipID","mock-bank","accounts",List.of(item))));
      }
     } else code=404;
    } catch(RuntimeException failure) {code=500;response=Map.of("error","Mock request invalid");}
    byte[] result=JSON.writeValueAsBytes(response);x.getResponseHeaders().set("Content-Type","application/json");
    x.sendResponseHeaders(code,result.length);x.getResponseBody().write(result);x.close();
   });server.start();return server;
  } catch(java.io.IOException failure) {throw new ExceptionInInitializerError(failure);}
 }
 HttpResponse<String> call(String method,String path,String body,String token,String key) throws Exception {
  var b=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).header("Content-Type","application/json");
  if(token!=null)b.header("Authorization","Bearer "+token);if(key!=null)b.header("Idempotency-Key",key);
  return HttpClient.newHttpClient().send(b.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
 }
 String create(String token,String key) throws Exception {
  var response=call("POST","/api/v1/bank-connections/consents",JSON.writeValueAsString(Map.of("vua","9999999999","from",FROM,"to",TO,"consentDays",1)),token,key);
  assertEquals(201,response.statusCode(),response.body());return JSON.readTree(response.body()).path("id").asString();
 }
 String approve(String localId) {
  String provider=consents.findById(UUID.fromString(localId)).orElseThrow().getProviderId();CONSENTS.put(provider,"ACTIVE");return provider;
 }
 String session(String token,String localId,String key) throws Exception {
  var response=call("POST","/api/v1/bank-connections/consents/"+localId+"/sessions",JSON.writeValueAsString(Map.of("from",FROM,"to",TO)),token,key);
  assertEquals(202,response.statusCode(),response.body());return JSON.readTree(response.body()).path("id").asString();
 }
 @Test void enforcesOwnershipIdempotencyAndConsentApproval() throws Exception {
  String token="user-"+UUID.randomUUID(),key=UUID.randomUUID().toString();
  String id=create(token,key);int count=CREATE_CALLS.get();assertEquals(id,create(token,key));assertEquals(count,CREATE_CALLS.get());
  assertEquals(404,call("GET","/api/v1/bank-connections/consents/"+id,null,"user-"+UUID.randomUUID(),null).statusCode());
  var blocked=call("POST","/api/v1/bank-connections/consents/"+id+"/sessions",JSON.writeValueAsString(Map.of("from",FROM,"to",TO)),token,UUID.randomUUID().toString());
  assertEquals(409,blocked.statusCode());
  var conflict=call("POST","/api/v1/bank-connections/consents",JSON.writeValueAsString(Map.of("vua","8888888888","from",FROM,"to",TO,"consentDays",1)),token,key);
  assertEquals(409,conflict.statusCode());
 }
 @Test void importsDeduplicatesAndStopsAfterConsentRevocation() throws Exception {
  String token="user-"+UUID.randomUUID(),id=create(token,UUID.randomUUID().toString());approve(id);
  String key=UUID.randomUUID().toString(),session=session(token,id,key);int calls=SESSION_CALLS.get();assertEquals(session,session(token,id,key));assertEquals(calls,SESSION_CALLS.get());
  assertEquals(200,call("POST","/api/v1/bank-connections/sessions/"+session+"/sync",null,token,null).statusCode());
  Instant retention=jdbc.queryForObject("SELECT data_expires_at FROM \""+SCHEMA+"\".bank_data_sessions WHERE id=?",java.sql.Timestamp.class,UUID.fromString(session)).toInstant();
  assertEquals(200,call("POST","/api/v1/bank-connections/sessions/"+session+"/sync",null,token,null).statusCode());
  assertEquals(retention,jdbc.queryForObject("SELECT data_expires_at FROM \""+SCHEMA+"\".bank_data_sessions WHERE id=?",java.sql.Timestamp.class,UUID.fromString(session)).toInstant());
  var result=call("GET","/api/v1/transactions",null,token,null);var json=JSON.readTree(result.body());
  assertEquals(200,result.statusCode(),result.body());assertEquals(1,json.path("total").asInt());assertEquals(0,new java.math.BigDecimal("25.50").compareTo(new java.math.BigDecimal(json.path("items").path(0).path("amount").asString())));
  assertEquals("INR",json.path("items").path(0).path("currency").asString());
  assertEquals("XXXX1234",json.path("items").path(0).path("maskedNumber").asString());
  assertEquals(0,JSON.readTree(call("GET","/api/v1/transactions",null,"user-"+UUID.randomUUID(),null).body()).path("total").asInt());
  assertEquals(200,call("POST","/api/v1/bank-connections/consents/"+id+"/revoke",null,token,null).statusCode());
  assertEquals(0,JSON.readTree(call("GET","/api/v1/transactions",null,token,null).body()).path("total").asInt());
  assertEquals(409,call("POST","/api/v1/bank-connections/sessions/"+session+"/sync",null,token,null).statusCode());
 }
 @Test void recordsExplicitSessionRejectionWithoutBlindRetry() throws Exception {
  String token="user-"+UUID.randomUUID(),id=create(token,UUID.randomUUID().toString());approve(id);
  REJECT_CREATES.add(consents.findById(UUID.fromString(id)).orElseThrow().getProviderId());
  String key=UUID.randomUUID().toString(),local=session(token,id,key);
  var result=call("GET","/api/v1/bank-connections/sessions/"+local,null,token,null);
  var body=JSON.readTree(result.body());assertEquals("CREATE_REJECTED",body.path("status").asString());
  assertEquals("SETU_HTTP_400",body.path("failureCode").asString());
  assertFalse(result.body().contains("private upstream detail"));
  int count=SESSION_CALLS.get();assertEquals(local,session(token,id,key));assertEquals(count,SESSION_CALLS.get());
 }
 @Test void doesNotImportFailedSessionEvenWithReadyAccountData() throws Exception {
  String token="user-"+UUID.randomUUID(),id=create(token,UUID.randomUUID().toString());approve(id);
  FAILED_SESSIONS.add(consents.findById(UUID.fromString(id)).orElseThrow().getProviderId());
  String local=session(token,id,UUID.randomUUID().toString());
  var result=call("POST","/api/v1/bank-connections/sessions/"+local+"/sync",null,token,null);
  assertEquals(200,result.statusCode());assertEquals("FAILED",JSON.readTree(result.body()).path("status").asString());
  assertEquals(0,JSON.readTree(call("GET","/api/v1/transactions",null,token,null).body()).path("total").asInt());
 }
 @Test void callbacksAreHintsAndReplaySafe() throws Exception {
  String token="user-"+UUID.randomUUID(),id=create(token,UUID.randomUUID().toString());
  String provider=consents.findById(UUID.fromString(id)).orElseThrow().getProviderId();
  String body=JSON.writeValueAsString(Map.of("type","CONSENT_STATUS_UPDATE","consentId",provider,"data",Map.of("status","ACTIVE")));
  assertEquals(401,call("POST","/api/v1/integrations/setu/webhook/wrong-token",body,null,null).statusCode());
  assertEquals(202,call("POST",CALLBACK,body,null,null).statusCode());long count=events.count();
  assertEquals(202,call("POST",CALLBACK,body,null,null).statusCode());assertEquals(count,events.count());
  worker.processPending();assertEquals("PENDING",consents.findById(UUID.fromString(id)).orElseThrow().getStatus());
  assertEquals(400,call("POST",CALLBACK,"{}",null,null).statusCode());
  assertEquals(413,call("POST",CALLBACK," ".repeat(65537),null,null).statusCode());
 }
 @Test void expiryHidesAndDeletesStoredTransactionsAndErrorsAreSanitized() throws Exception {
  String token="user-"+UUID.randomUUID(),id=create(token,UUID.randomUUID().toString());String provider=approve(id);
  String session=session(token,id,UUID.randomUUID().toString());
  assertEquals(200,call("POST","/api/v1/bank-connections/sessions/"+session+"/sync",null,token,null).statusCode());
  jdbc.update("UPDATE "+SCHEMA+".bank_transactions SET expires_at=? WHERE owner_id=?",java.sql.Timestamp.from(Instant.now().minusSeconds(1)),UUID.fromString(token.substring(5)));
  assertEquals(0,JSON.readTree(call("GET","/api/v1/transactions",null,token,null).body()).path("total").asInt());worker.expireData();
  assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM "+SCHEMA+".bank_transactions WHERE owner_id=?",Integer.class,UUID.fromString(token.substring(5))));
  CONSENTS.put(provider,"ERROR");var failure=call("POST","/api/v1/bank-connections/consents/"+id+"/refresh",null,token,null);
  assertEquals(502,failure.statusCode());assertFalse(failure.body().contains("do-not-expose"));
 }
}
