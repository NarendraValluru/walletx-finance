package com.example.walletxfinance.repository;
import com.example.backendutil.finance.BankConsentEntity;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;

public interface BankConsentRepository extends JpaRepository<BankConsentEntity, UUID> {
    Optional<BankConsentEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
    Optional<BankConsentEntity> findByOwnerIdAndRequestKey(UUID ownerId, String requestKey);
    Optional<BankConsentEntity> findByProviderId(String providerId);
    Page<BankConsentEntity> findByOwnerId(UUID ownerId, Pageable page);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from BankConsent c where c.id = :id")
    Optional<BankConsentEntity> lockById(UUID id);
}

