package com.example.walletxfinance.service;
import com.example.walletxfinance.config.SetuProperties;
import com.example.walletxfinance.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

@Component @RequiredArgsConstructor
public class SetuSyncWorker {
    private final SetuProperties properties;
    private final BankConnectionService service;
    private final BankConsentRepository consents;
    private final BankDataSessionRepository sessions;
    private final BankTransactionRepository transactions;
    private final SetuWebhookEventRepository events;
    private final TransactionTemplate bankTransactions;

    @Scheduled(fixedDelayString="${walletx.setu.poll-delay-ms:15000}")
    public void scheduled() {
        if(properties.isEnabled() && properties.isPollingEnabled()) processPending();
    }
    public void processPending() {
        for(var event:events.findTop20ByStatusAndNextAttemptBeforeOrderByCreatedAtAsc("NEW",Instant.now())) {
            try {
                var consent=consents.findByProviderId(event.getProviderConsentId()).orElseThrow();
                service.refreshConsent(consent.getOwnerId(),consent.getId());
                if(event.getProviderSessionId()!=null) {
                    var session=sessions.findByProviderId(event.getProviderSessionId()).orElseThrow();
                    if(!session.getConsentId().equals(consent.getId())) throw new IllegalArgumentException();
                    service.processSession(session.getId());
                }
                bankTransactions.executeWithoutResult(tx -> events.lockById(event.getId()).ifPresent(e -> e.setStatus("PROCESSED")));
            } catch(RuntimeException failed) {
                bankTransactions.executeWithoutResult(tx -> events.lockById(event.getId()).ifPresent(e -> {
                    e.setAttempts(e.getAttempts()+1);e.setFailureCode("RECONCILIATION_FAILED");
                    e.setNextAttempt(Instant.now().plusSeconds(30L*e.getAttempts()));
                    if(e.getAttempts()>=5) e.setStatus("FAILED");
                }));
            }
        }
        // Reconcile known data sessions even when callbacks are delayed or missed.
        for(var session:sessions.findTop20ByStatusInAndNextAttemptBeforeOrderByCreatedAtAsc(List.of("PENDING","PARTIAL"),Instant.now())) {
            if(session.getCreatedAt().isBefore(Instant.now().minusSeconds(1800))) {
                bankTransactions.executeWithoutResult(tx -> sessions.lockById(session.getId()).ifPresent(s -> {s.setStatus("SYNC_TIMEOUT");s.setFailureCode("POLL_WINDOW_EXCEEDED");}));
                continue;
            }
            try {service.processSession(session.getId());} catch(RuntimeException failed) {service.markSessionFailure(session.getId());}
        }
    }
    @Scheduled(fixedDelay=60000)
    public void expireData() { transactions.deleteExpired(Instant.now()); }
}

