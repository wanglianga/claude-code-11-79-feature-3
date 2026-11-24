package com.ctrial.repo;

import com.ctrial.model.ConstraintFinding;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConstraintFindingRepo extends JpaRepository<ConstraintFinding, Long> {
    List<ConstraintFinding> findAllByOrderByIdAsc();
    List<ConstraintFinding> findByViolatedTrueOrderByIdAsc();
    void deleteByGroupKey(String groupKey);
    List<ConstraintFinding> findByGroupKeyOrderByCellKeyAsc(String groupKey);
}
