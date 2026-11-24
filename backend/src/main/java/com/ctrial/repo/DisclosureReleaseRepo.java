package com.ctrial.repo;

import com.ctrial.model.DisclosureRelease;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DisclosureReleaseRepo extends JpaRepository<DisclosureRelease, Long> {
    Optional<DisclosureRelease> findByCode(String code);
    List<DisclosureRelease> findAllByOrderByRequestedAtAscIdAsc();
    List<DisclosureRelease> findByLineageOrderByRequestedAtAscIdAsc(String lineage);
    long countByLineage(String lineage);
    List<DisclosureRelease> findBySurfaceIdOrderByRequestedAtAsc(Long surfaceId);
}
