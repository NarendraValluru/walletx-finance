package com.example.walletxfinance.service;

import com.example.backendutil.finance.BankConsentEntity;
import com.example.backendutil.finance.BankDataSessionEntity;
import com.example.walletxfinance.exception.BankIntegrationException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

@Service @RequiredArgsConstructor
public class BankDataIngestion {
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private String schema() {
        String value=environment.getProperty("spring.flyway.placeholders.financeSchema","finance");
        if(!value.matches("[a-z][a-z0-9_]{0,62}")) throw new IllegalStateException("Invalid configured finance schema");
        return "\""+value+"\".";
    }
    public void ingest(BankConsentEntity consent, BankDataSessionEntity localSession, JsonNode consentData, JsonNode session) {
        Instant now=Instant.now();
        Instant approvedExpiry=switch(String.valueOf(consent.getDataLifeUnit())) {
            case "DAY" -> now.atZone(ZoneOffset.UTC).plusDays(consent.getDataLifeValue()).toInstant();
            case "MONTH" -> now.atZone(ZoneOffset.UTC).plusMonths(consent.getDataLifeValue()).toInstant();
            case "YEAR" -> now.atZone(ZoneOffset.UTC).plusYears(consent.getDataLifeValue()).toInstant();
            default -> throw new BankIntegrationException(502,"Approved consent has no supported finite data life");
        };
        if(localSession.getDataExpiresAt()==null || approvedExpiry.isBefore(localSession.getDataExpiresAt())) localSession.setDataExpiresAt(approvedExpiry);
        Instant expiry=localSession.getDataExpiresAt();
        if(!expiry.isAfter(now)) throw new BankIntegrationException(409,"Session data retention has expired; request a new data session");
        Set<String> allowed=new HashSet<>();
        for(JsonNode account:consentData.path("detail").path("accounts")) {
            allowed.add(account.path("fipId").asString("")+":"+account.path("linkRefNumber").asString(""));
        }
        String ns=schema();
        for(JsonNode fip:session.path("fips")) {
            String fipId=required(fip,"fipID");
            for(JsonNode item:fip.path("accounts")) {
                if(!List.of("DELIVERED","READY").contains(item.path("status").asString(""))) continue;
                String ref=required(item,"linkRefNumber");
                if(!allowed.contains(fipId+":"+ref)) throw new BankIntegrationException(502,"Returned account is not in the approved consent");
                JsonNode account=item.path("data").path("account");
                if(!"deposit".equalsIgnoreCase(account.path("type").asString(""))) throw new BankIntegrationException(502,"Only DEPOSIT data is supported");
                UUID accountId=jdbc.queryForObject("INSERT INTO "+ns+"bank_accounts (id,owner_id,fip_id,link_ref,masked_number,currency,account_type,last_synced,created_at,version) VALUES (?,?,?,?,?,?,?,?,?,0) ON CONFLICT(owner_id,fip_id,link_ref) DO UPDATE SET masked_number=excluded.masked_number,currency=excluded.currency,account_type=excluded.account_type,last_synced=excluded.last_synced RETURNING id",UUID.class,
                        UUID.randomUUID(),consent.getOwnerId(),fipId,ref,item.path("maskedAccNumber").asString(""),
                        account.path("summary").path("currency").asString(null),account.path("summary").path("type").asString(null),
                        java.sql.Timestamp.from(now),java.sql.Timestamp.from(now));
                JsonNode transactions=account.path("transactions");
                JsonNode rows=transactions.isArray()?transactions:transactions.path("transaction");
                Iterable<JsonNode> items=rows.isObject()?List.of(rows):rows;
                for(JsonNode row:items) {
                    String txn=required(row,"txnId"),direction=required(row,"type");
                    if(!List.of("DEBIT","CREDIT").contains(direction)) throw new BankIntegrationException(502,"Unsupported transaction direction");
                    BigDecimal amount;
                    Instant occurred;
                    try {
                        amount=new BigDecimal(required(row,"amount"));
                        if(amount.signum()<0 || amount.scale()>4 || amount.precision()-amount.scale()>15) throw new IllegalArgumentException();
                        occurred=Instant.parse(required(row,"transactionTimestamp"));
                    } catch(RuntimeException invalid) { throw new BankIntegrationException(502,"Invalid provider transaction amount or timestamp"); }
                    String narration=row.path("narration").asString("");
                    if(narration.length()>2000) throw new BankIntegrationException(502,"Provider narration exceeds supported length");
                    jdbc.update("INSERT INTO "+ns+"bank_transactions (id,owner_id,account_id,consent_id,provider_txn_id,amount,direction,occurred_at,narration,mode,expires_at,created_at,version) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,0) ON CONFLICT(account_id,provider_txn_id) DO UPDATE SET amount=excluded.amount,direction=excluded.direction,occurred_at=excluded.occurred_at,narration=excluded.narration,mode=excluded.mode,expires_at=excluded.expires_at,consent_id=excluded.consent_id",UUID.randomUUID(),consent.getOwnerId(),accountId,consent.getId(),txn,amount,direction,java.sql.Timestamp.from(occurred),narration,row.path("mode").asString(""),java.sql.Timestamp.from(expiry),java.sql.Timestamp.from(now));
                }
            }
        }
    }
    private String required(JsonNode node,String field) {
        String value=node.path(field).asString("");
        if(value.isBlank() || value.length()>255) throw new BankIntegrationException(502,"Provider data is missing a supported "+field);
        return value;
    }
}


