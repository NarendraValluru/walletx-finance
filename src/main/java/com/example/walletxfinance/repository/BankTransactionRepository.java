package com.example.walletxfinance.repository;
import com.example.backendutil.finance.BankTransactionEntity;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;
import org.springframework.transaction.annotation.Transactional;
public interface BankTransactionRepository extends JpaRepository<BankTransactionEntity, UUID> {
    Page<BankTransactionEntity> findByOwnerIdAndExpiresAtAfter(UUID ownerId, Instant now, Pageable page);
    @Modifying @Transactional
    @Query("delete from BankTransaction t where t.expiresAt < :now")
    int deleteExpired(Instant now);
    @Modifying @Transactional
    @Query("delete from BankTransaction t where t.consentId = :consentId")
    int deleteForConsent(UUID consentId);
}

