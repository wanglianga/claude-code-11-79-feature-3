package com.ctrial.repo;

import com.ctrial.model.DisclosureSurface;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisclosureSurfaceRepo extends JpaRepository<DisclosureSurface, Long> {
    Optional<DisclosureSurface> findByCode(String code);
    List<DisclosureSurface> findAllByOrderByLineageAscVersionAsc();
    List<DisclosureSurface> findByLineageOrderByVersionAsc(String lineage);
}
