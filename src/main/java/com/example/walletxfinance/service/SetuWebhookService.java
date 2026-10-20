package com.example.walletxfinance.service;
import com.example.walletxfinance.config.SetuProperties;
import com.example.backendutil.finance.SetuWebhookEventEntity;
import com.example.walletxfinance.repository.SetuWebhookEventRepository;
import com.example.walletxfinance.exception.BankIntegrationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

@Service @RequiredArgsConstructor
public class SetuWebhookService {
    private final SetuProperties properties;
    private final SetuWebhookEventRepository events;
    public void authenticate(String token) {
        if(!properties.isEnabled() || properties.getCallbackToken().length()<40) throw new BankIntegrationException(503,"Setu callbacks are disabled");
        if(!MessageDigest.isEqual(properties.getCallbackToken().getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8))) throw new BankIntegrationException(401,"Invalid callback credential");
    }
    public void accept(byte[] payload) {
        try {
            var data=JsonMapper.builder().build().readTree(payload);
            String type=data.path("type").asString(""),consent=data.path("consentId").asString("");
            if(!List.of("CONSENT_STATUS_UPDATE","SESSION_STATUS_UPDATE","FI_DATA_READY","FI_DATA_FAILED").contains(type) || !consent.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException();
            String session=data.path("dataSessionId").asString("");
            if(!session.isBlank() && !session.matches("[A-Za-z0-9_-]{1,255}")) throw new IllegalArgumentException();
            String hash=BankConnectionService.digest(new String(payload,StandardCharsets.UTF_8));
            if(events.existsByEventKey(hash)) return;
            var event=new SetuWebhookEventEntity();event.setEventKey(hash);event.setProviderConsentId(consent);event.setProviderSessionId(session.isBlank()?null:session);
            event.setEventType(type);event.setStatus("NEW");event.setNextAttempt(Instant.now());
            // Payload bank data/status are intentionally discarded. Worker re-queries Setu.
            try {events.saveAndFlush(event);} catch(org.springframework.dao.DataIntegrityViolationException replay) {
                if(!events.existsByEventKey(hash)) throw replay;
            }
        } catch(BankIntegrationException known) {throw known;}
        catch(org.springframework.dao.DataAccessException unavailable) {throw unavailable;}
        catch(RuntimeException malformed) {throw new BankIntegrationException(400,"Malformed or unsupported Setu notification");}
    }
}

