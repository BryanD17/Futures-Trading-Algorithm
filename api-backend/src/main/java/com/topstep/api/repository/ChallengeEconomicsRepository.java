package com.topstep.api.repository;

import com.topstep.api.entity.ChallengeEconomicsEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChallengeEconomicsRepository extends JpaRepository<ChallengeEconomicsEntity, Long> {

    List<ChallengeEconomicsEntity> findByAccountId(String accountId);

    List<ChallengeEconomicsEntity> findAllByOrderByRecordedAtDesc();

    @Query("SELECT SUM(c.feePaid) FROM ChallengeEconomicsEntity c")
    Double totalFeesPaid();

    @Query("SELECT SUM(c.payoutReceived) FROM ChallengeEconomicsEntity c")
    Double totalPayoutsReceived();

    @Query("SELECT SUM(c.netProfit) FROM ChallengeEconomicsEntity c")
    Double totalNetProfit();

    @Query("SELECT COUNT(c) FROM ChallengeEconomicsEntity c WHERE c.outcome = 'PASSED'")
    long countPassed();

    @Query("SELECT COUNT(c) FROM ChallengeEconomicsEntity c")
    long countTotal();
}
