package com.example.walletxfinance.repository;
import com.example.backendutil.finance.BankDataSessionEntity;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;

public interface BankDataSessionRepository extends JpaRepository<BankDataSessionEntity, UUID> {
    Optional<BankDataSessionEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    Optional<BankDataSessionEntity> findByOwnerIdAndRequestKey(UUID ownerId, String requestKey);
    Optional<BankDataSessionEntity> findByProviderId(String providerId);
    Page<BankDataSessionEntity> findByConsentIdAndOwnerId(UUID consentId, UUID ownerId, Pageable page);
    List<BankDataSessionEntity> findTop20ByStatusInAndNextAttemptBeforeOrderByCreatedAtAsc(Collection<String> statuses, Instant now);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from BankDataSession s where s.id = :id")
    Optional<BankDataSessionEntity> lockById(UUID id);
}

