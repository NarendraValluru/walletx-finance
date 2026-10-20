package com.example.walletxfinance.dto;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.*;
public final class BankDtos {
 private BankDtos() {}
 public record ConsentRequest(@NotBlank @Pattern(regexp="[0-9]{10}(@[A-Za-z0-9._-]+)?") String vua,
        @NotNull Instant from, @NotNull Instant to, @Min(1) @Max(30) int consentDays) {}
 public record SessionRequest(@NotNull Instant from, @NotNull Instant to) {}
 public record ConsentView(UUID id,String status,String approvalUrl,Instant from,Instant to,Instant expiresAt,Instant createdAt) {}
 public record SessionView(UUID id,UUID consentId,String status,int attempts,String failureCode,Instant createdAt) {}
 public record AccountView(UUID id,String fipId,String maskedNumber,String accountType,String currency,Instant lastSynced) {}
 public record TransactionView(UUID id,UUID accountId,String providerTxnId,BigDecimal amount,String direction,Instant occurredAt,String narration,String mode) {}
 public record PageView<T>(List<T> items,long total,int page,int size) {}
}
