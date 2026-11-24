package com.ctrial.repo;

import com.ctrial.model.ReleaseRelation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ReleaseRelationRepo extends JpaRepository<ReleaseRelation, Long> {
    List<ReleaseRelation> findAllByOrderByIdAsc();
    List<ReleaseRelation> findByFromCodeOrToCode(String fromCode, String toCode);
    long countByFromCodeAndToCode(String fromCode, String toCode);
}
