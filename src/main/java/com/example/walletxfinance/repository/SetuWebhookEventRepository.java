package com.example.walletxfinance.repository;
import com.example.backendutil.finance.SetuWebhookEventEntity;
import java.util.*;
import java.time.Instant;
import org.springframework.data.jpa.repository.*;

public interface SetuWebhookEventRepository extends JpaRepository<SetuWebhookEventEntity, UUID> {
    boolean existsByEventKey(String eventKey);
    List<SetuWebhookEventEntity> findTop20ByStatusAndNextAttemptBeforeOrderByCreatedAtAsc(String status, Instant now);
    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from SetuWebhookEvent e where e.id = :id")
    Optional<SetuWebhookEventEntity> lockById(UUID id);
}

