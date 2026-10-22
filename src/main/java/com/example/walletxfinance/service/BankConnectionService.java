package com.example.walletxfinance.service;

import com.example.backendutil.finance.BankConsentEntity;
import com.example.backendutil.finance.BankDataSessionEntity;
import com.example.walletxfinance.config.SetuProperties;
import com.example.walletxfinance.dto.BankDtos.*;
import com.example.walletxfinance.exception.BankIntegrationException;
import com.example.walletxfinance.integration.SetuClient;
import com.example.walletxfinance.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.*;

@Service @RequiredArgsConstructor
public class BankConnectionService {
    private final BankConsentRepository consents;
    private final BankDataSessionRepository sessions;
    private final BankAccountRepository accounts;
    private final BankTransactionRepository transactions;
    private final SetuClient setu;
    private final SetuProperties properties;
    private final TransactionTemplate bankTransactions;
    private final BankDataIngestion ingestion;

    public ConsentView createConsent(UUID owner,String key,ConsentRequest request) {
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}")) throw error(400,"Use an Idempotency-Key of 8-100 letters, digits, underscores or hyphens");
        if(!properties.isEnabled()) throw error(503,"Setu integration is not configured");
        range(request.from(),request.to());
        String fingerprint=digest(request.vua()+"|"+request.from()+"|"+request.to()+"|"+request.consentDays());
        var old=consents.findByOwnerIdAndRequestKey(owner,key);
        if(old.isPresent()) {
            if(!fingerprint.equals(old.get().getPayloadHash())) throw error(409,"Idempotency-Key was used with a different request");
            return view(old.get());
        }
        var consent=new BankConsentEntity(); consent.setOwnerId(owner); consent.setRequestKey(key); consent.setPayloadHash(fingerprint);
        consent.setStatus("CREATING");consent.setFromTime(request.from());consent.setToTime(request.to());
        consent=consents.saveAndFlush(consent); // Durable reservation before an external create; never blindly retry a POST.
        try {
            var payload=Map.of("vua",request.vua(),"consentDuration",Map.of("unit","DAY","value",String.valueOf(request.consentDays())),
                    "dataRange",Map.of("from",request.from().toString(),"to",request.to().toString()),"redirectUrl",properties.getRedirectUrl().toString());
            JsonNode response=setu.createConsent(payload);
            consent.setProviderId(reference(response,"id"));
            consent.setApprovalUrl(response.path("url").asString(""));
            consent.setStatus(response.path("status").asString("PENDING"));
            applyDetails(consent,response);
            return view(consents.saveAndFlush(consent));
        } catch(RuntimeException failure) {
            consent.setStatus("CREATE_UNKNOWN");consents.saveAndFlush(consent);
            if(failure instanceof BankIntegrationException known) throw known;
            throw error(502,"Consent creation needs reconciliation; do not repeat it with a new key");
        }
    }
    public ConsentView consent(UUID owner,UUID id) { return view(owned(owner,id)); }
    public PageView<ConsentView> consents(UUID owner,int page,int size) { return page(consents.findByOwnerId(owner,paging(page,size)),this::view); }
    public ConsentView refreshConsent(UUID owner,UUID id) {
        owned(owner,id);
        return bankTransactions.execute(status -> view(refreshLocked(id)));
    }
    private BankConsentEntity owned(UUID owner, UUID id) { return consents.findByIdAndOwnerId(id,owner).orElseThrow(() -> error(404,"Consent not found")); }
    public BankConsentEntity refreshLocked(UUID id) {
        var c=consents.lockById(id).orElseThrow(() -> error(404,"Consent not found"));
        if(c.getProviderId()==null) throw error(409,"Provider creation is unresolved; reconcile before continuing");
        JsonNode response=setu.consent(c.getProviderId());
        if(!c.getProviderId().equals(response.path("id").asString(""))) throw error(502,"Consent reference mismatch");
        c.setStatus(response.path("status").asString("UNKNOWN")); applyDetails(c,response);
        if(List.of("REVOKED","EXPIRED","REJECTED").contains(c.getStatus())) transactions.deleteForConsent(c.getId());
        return c;
    }
    public ConsentView revoke(UUID owner,UUID id) {
        var c=owned(owner,id);
        if(c.getProviderId()==null) throw error(409,"Consent has no provider reference");
        setu.revoke(c.getProviderId());
        // Verify authoritative status after revoke; do not manufacture success from a webhook/browser redirect.
        return refreshConsent(owner,id);
    }
    public SessionView createSession(UUID owner,UUID consentId,String key,SessionRequest request) {
        if(!properties.isEnabled()) throw error(503,"Setu integration is not configured");
        range(request.from(),request.to());
        if(key==null || !key.matches("[A-Za-z0-9_-]{8,100}")) throw error(400,"A valid Idempotency-Key is required");
        String fingerprint=digest(consentId+"|"+request.from()+"|"+request.to());
        var old=sessions.findByOwnerIdAndRequestKey(owner,key);
        if(old.isPresent()) {
            if(!fingerprint.equals(old.get().getPayloadHash())) throw error(409,"Idempotency-Key was used with a different request");
            return sessionView(old.get());
        }
        owned(owner,consentId);
        return bankTransactions.execute(tx -> {
            var c=refreshLocked(consentId); active(c);
            if(request.from().isBefore(c.getFromTime()) || request.to().isAfter(c.getToTime())) throw error(400,"Requested range exceeds consent range");
            var session=new BankDataSessionEntity();session.setOwnerId(owner);session.setConsentId(c.getId());session.setStatus("CREATING");
            session.setFromTime(request.from());session.setToTime(request.to());session.setRequestKey(key);session.setPayloadHash(fingerprint);
            session=sessions.saveAndFlush(session);
            try {
                var response=setu.createSession(Map.of("consentId",c.getProviderId(),"dataRange",Map.of("from",request.from().toString(),"to",request.to().toString()),"format","json"));
                session.setProviderId(reference(response,"id"));session.setStatus(response.path("status").asString("PENDING"));
                session.setNextAttempt(Instant.now());
                return sessionView(session);
            } catch(BankIntegrationException failure) {
                Integer code=failure.getProviderStatus();
                boolean rejected=code!=null && code>=400 && code<500 && code!=408;
                session.setStatus(rejected?"CREATE_REJECTED":"CREATE_UNKNOWN");
                session.setFailureCode(code==null?"CREATE_UNKNOWN":"SETU_HTTP_"+code);
                // Commit uncertain state rather than retrying a provider POST.
                return sessionView(session);
            }
        });
    }
    public SessionView session(UUID owner,UUID id) {
        var s=sessions.findByIdAndOwnerId(id,owner).orElseThrow(() -> error(404,"Data session not found"));return sessionView(s);
    }
    public PageView<SessionView> sessions(UUID owner,UUID consentId,int page,int size) {
        owned(owner,consentId);return page(sessions.findByConsentIdAndOwnerId(consentId,owner,paging(page,size)),this::sessionView);
    }
    public SessionView sync(UUID owner,UUID id) {
        sessions.findByIdAndOwnerId(id,owner).orElseThrow(() -> error(404,"Data session not found"));
        processSession(id);return session(owner,id);
    }
    public void processSession(UUID id) {
        var existing=sessions.findById(id).orElseThrow(() -> error(404,"Data session not found"));
        refreshConsent(existing.getOwnerId(),existing.getConsentId()); // Persist revocations even if sync must be denied.
        bankTransactions.executeWithoutResult(tx -> {
            var s=sessions.lockById(id).orElseThrow(() -> error(404,"Data session not found"));
            if(s.getProviderId()==null) throw error(409,"Data-session creation needs reconciliation");
            var c=consents.lockById(s.getConsentId()).orElseThrow(); active(c);
            JsonNode data=setu.session(s.getProviderId());
            if(data.has("consentId") && !c.getProviderId().equals(data.path("consentId").asString(""))) throw error(502,"Session consent mismatch");
            String state=data.path("status").asString("UNKNOWN");
            if(List.of("PARTIAL","COMPLETED").contains(state)) {
                var current=setu.consent(c.getProviderId());
                c.setStatus(current.path("status").asString("UNKNOWN"));applyDetails(c,current);
                if(!List.of("ACTIVE","APPROVED").contains(c.getStatus())) {
                    transactions.deleteForConsent(c.getId());s.setStatus("CONSENT_INACTIVE");return;
                }
                active(c);ingestion.ingest(c,s,current,data);
            }
            if(!List.of("PENDING","PARTIAL","COMPLETED","EXPIRED","FAILED").contains(state)) throw error(502,"Unsupported provider session state");
            s.setStatus(state);s.setFailureCode(null);s.setAttempts(0);s.setNextAttempt(Instant.now().plusSeconds(30));
        });
    }
    public void markSessionFailure(UUID id) {
        bankTransactions.executeWithoutResult(tx -> sessions.lockById(id).ifPresent(s -> {
            s.setAttempts(s.getAttempts()+1);s.setFailureCode("PROVIDER_SYNC_FAILED");
            if(s.getAttempts()>=5) s.setStatus("SYNC_FAILED");
            s.setNextAttempt(Instant.now().plusSeconds(Math.min(600,15L << Math.min(5,s.getAttempts()))));
        }));
    }
    public PageView<AccountView> accounts(UUID owner,int page,int size) {
        return page(accounts.findByOwnerId(owner,paging(page,size)),a -> new AccountView(a.getId(),a.getFipId(),a.getMaskedNumber(),a.getAccountType(),a.getCurrency(),a.getLastSynced()));
    }
    public PageView<TransactionView> transactions(UUID owner,int page,int size) {
        var result=transactions.findByOwnerIdAndExpiresAtAfter(owner,Instant.now(),PageRequest.of(Math.max(0,page),Math.min(100,Math.max(1,size)),Sort.by("occurredAt").descending()));
        var accountIds=result.getContent().stream().map(t -> t.getAccountId()).distinct().toList();
        // Fetch metadata once per page, scoped to the owner; avoid one lookup per transaction.
        var metadata=accountIds.isEmpty()?Map.<UUID,com.example.backendutil.finance.BankAccountEntity>of():
                accounts.findByOwnerIdAndIdIn(owner,accountIds).stream().collect(java.util.stream.Collectors.toMap(a -> a.getId(),a -> a));
        return page(result,t -> {
            var account=metadata.get(t.getAccountId());
            return new TransactionView(t.getId(),t.getAccountId(),t.getProviderTxnId(),t.getAmount(),t.getDirection(),t.getOccurredAt(),t.getNarration(),t.getMode(),
                    account==null?null:account.getCurrency(),account==null?null:account.getMaskedNumber());
        });
    }
    private void active(BankConsentEntity c) {
        if(!List.of("ACTIVE","APPROVED").contains(c.getStatus()) || c.getExpiresAt()==null || !c.getExpiresAt().isAfter(Instant.now())) throw error(409,"Consent is not active or has expired");
        if(c.getDataLifeValue()<1 || !List.of("DAY","MONTH","YEAR").contains(String.valueOf(c.getDataLifeUnit()))) throw error(409,"A finite approved data life is required");
    }
    private void applyDetails(BankConsentEntity c, JsonNode data) {
        JsonNode detail=data.path("detail");
        String end=detail.path("consentExpiry").asString("");
        if(!end.isBlank()) c.setExpiresAt(Instant.parse(end));
        var life=detail.path("dataLife");
        if(life.has("unit")) {
            int value=life.path("value").asInt(0);
            if(value<1 || value>3650 || !List.of("DAY","MONTH","YEAR").contains(life.path("unit").asString(""))) throw error(502,"Unsupported consent data-life policy");
            c.setDataLifeUnit(life.path("unit").asString());c.setDataLifeValue(value);
        }
        if(detail.path("dataRange").has("from")) {
            c.setFromTime(Instant.parse(detail.path("dataRange").path("from").asString()));c.setToTime(Instant.parse(detail.path("dataRange").path("to").asString()));
        }
    }
    private void range(Instant from,Instant to) { if(!from.isBefore(to)) throw error(400,"from must precede to"); }
    private String reference(JsonNode response,String key) {
        String id=response.path(key).asString("");if(!id.matches("[A-Za-z0-9_-]{1,255}")) throw error(502,"Missing provider reference");return id;
    }
    private PageRequest paging(int page,int size) {return PageRequest.of(Math.max(0,page),Math.min(100,Math.max(1,size)),Sort.by("createdAt").descending());}
    private <T,V> PageView<V> page(Page<T> p,java.util.function.Function<T,V> mapper) {return new PageView<>(p.getContent().stream().map(mapper).toList(),p.getTotalElements(),p.getNumber(),p.getSize());}
    private ConsentView view(BankConsentEntity c) {return new ConsentView(c.getId(),c.getStatus(),c.getApprovalUrl(),c.getFromTime(),c.getToTime(),c.getExpiresAt(),c.getCreatedAt());}
    private SessionView sessionView(BankDataSessionEntity s) {return new SessionView(s.getId(),s.getConsentId(),s.getStatus(),s.getAttempts(),s.getFailureCode(),s.getCreatedAt());}
    public static BankIntegrationException error(int code,String detail) {return new BankIntegrationException(code,detail);}
    public static String digest(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}



