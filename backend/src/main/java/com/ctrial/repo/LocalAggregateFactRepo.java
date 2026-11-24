package com.ctrial.repo;

import com.ctrial.model.LocalAggregateFact;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface LocalAggregateFactRepo extends JpaRepository<LocalAggregateFact, Long> {
    List<LocalAggregateFact> findAllByOrderByCenterAscAgeBandAscPeriodAsc();
    long countByCenter(String center);
}
