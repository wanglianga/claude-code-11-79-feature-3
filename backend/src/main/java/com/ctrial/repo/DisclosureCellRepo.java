package com.ctrial.repo;

import com.ctrial.model.DisclosureCell;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DisclosureCellRepo extends JpaRepository<DisclosureCell, Long> {
    List<DisclosureCell> findByReleaseIdOrderByDimKeyAsc(Long releaseId);
}
