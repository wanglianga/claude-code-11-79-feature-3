package com.ctrial.model;

import com.ctrial.model.Enums.RelationType;
import com.ctrial.model.Enums.RiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Directed edge of the release relation graph: how two releases relate, and how risky the pair is. */
@Entity
@Table(name = "release_relation", indexes = {
        @Index(name = "idx_relation_from", columnList = "from_code"),
        @Index(name = "idx_relation_to", columnList = "to_code")
})
@Getter
@Setter
@NoArgsConstructor
public class ReleaseRelation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_code", nullable = false, length = 80)
    private String fromCode;

    @Column(name = "to_code", nullable = false, length = 80)
    private String toCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RelationType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel risk;

    /** Overlap of the two cohorts' atomic-cell coverage, 0..1. */
    @Column(name = "overlap_fraction", nullable = false)
    private double overlapFraction;

    /** Atoms covered by both releases (CSV). Grouping metadata only. */
    @Column(name = "shared_atoms", length = 2000)
    private String sharedAtoms;

    @Column(name = "smallest_residual_denominator", nullable = false)
    private int smallestResidualDenominator;

    @Column(length = 800)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
