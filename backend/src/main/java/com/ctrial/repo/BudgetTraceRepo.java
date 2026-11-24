package com.ctrial.repo;

import com.ctrial.model.BudgetTrace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface BudgetTraceRepo extends JpaRepository<BudgetTrace, Long> {
    List<BudgetTrace> findAllByOrderByLineageAscSeqAscIdAsc();
    List<BudgetTrace> findByLineageOrderBySeqAscIdAsc(String lineage);
    long countByLineage(String lineage);
}
