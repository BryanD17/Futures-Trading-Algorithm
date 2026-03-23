package com.topstep.api.repository;

import com.topstep.api.entity.AccountLifecycleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface AccountLifecycleRepository extends JpaRepository<AccountLifecycleEntity, Long> {

    List<AccountLifecycleEntity> findByAccountIdOrderByStartedAtDesc(String accountId);

    Optional<AccountLifecycleEntity> findFirstByAccountIdOrderByStartedAtDesc(String accountId);

    List<AccountLifecycleEntity> findByPhase(String phase);

    List<AccountLifecycleEntity> findByOutcome(String outcome);

    @Query("SELECT COUNT(a) FROM AccountLifecycleEntity a WHERE a.outcome = 'PASSED'")
    long countPassed();

    @Query("SELECT COUNT(a) FROM AccountLifecycleEntity a WHERE a.outcome IS NOT NULL")
    long countCompleted();

    @Query("SELECT AVG(a.tradingDays) FROM AccountLifecycleEntity a WHERE a.outcome = 'PASSED'")
    Double avgTradingDaysToPass();
}
