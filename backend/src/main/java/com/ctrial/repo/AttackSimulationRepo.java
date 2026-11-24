package com.ctrial.repo;

import com.ctrial.model.AttackSimulation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AttackSimulationRepo extends JpaRepository<AttackSimulation, Long> {
    Optional<AttackSimulation> findByCode(String code);
    List<AttackSimulation> findAllByOrderByCreatedAtDesc();
}
