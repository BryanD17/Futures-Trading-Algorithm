package com.topstep.api.repository;

import com.topstep.api.entity.EquityPathEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;

@Repository
public interface EquityPathRepository extends JpaRepository<EquityPathEntity, Long> {

    List<EquityPathEntity> findByAccountIdOrderByDateAsc(String accountId);

    List<EquityPathEntity> findByAccountIdAndDateBetweenOrderByDateAsc(
        String accountId, LocalDate startDate, LocalDate endDate);

    List<EquityPathEntity> findByAccountIdAndDateAfterOrderByDateAsc(
        String accountId, LocalDate afterDate);
}
