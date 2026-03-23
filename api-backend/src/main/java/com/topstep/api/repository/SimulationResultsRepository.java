package com.topstep.api.repository;

import com.topstep.api.entity.SimulationResultEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface SimulationResultsRepository extends JpaRepository<SimulationResultEntity, Long> {

    List<SimulationResultEntity> findAllByOrderByRunAtDesc();

    List<SimulationResultEntity> findTop10ByOrderByRunAtDesc();
}
