package com.example.walletxfinance.repository;
import com.example.backendutil.finance.BankAccountEntity;
import java.util.*;

import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.*;

public interface BankAccountRepository extends JpaRepository<BankAccountEntity, UUID> {
    Page<BankAccountEntity> findByOwnerId(UUID ownerId, Pageable page);
}

