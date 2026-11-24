package com.ctrial.model;

import com.ctrial.model.Enums.RiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A tracked <b>constraint group</b>: the tightest interval an attacker can pin
 * for one atomic cell by combining every released table, the public background
 * totals and the public enrollment bounds. When the interval collapses (width
 * 0) or its upper bound drops below the risk lower bound, the group is a
 * finding — even though each contributing table passed the minimum-group test
 * on its own.
 */
@Entity
@Table(name = "constraint_finding", indexes = {
        @Index(name = "idx_constraint_group", columnList = "group_key")
})
@Getter
@Setter
@NoArgsConstructor
public class ConstraintFinding {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Connected component of releases sharing unknown cells (by lineage). */
    @Column(name = "group_key", nullable = false, length = 120)
    private String groupKey;

    /** Atomic cell key, e.g. "CENTER=CN-02|AGE_BAND=C|PERIOD=P2". */
    @Column(name = "cell_key", nullable = false, length = 160)
    private String cellKey;

    @Column(name = "dims", nullable = false, length = 200)
    private String dims;

    @Column(name = "lower_bound", nullable = false)
    private int lowerBound;

    @Column(name = "upper_bound", nullable = false)
    private int upperBound;

    @Column(nullable = false)
    private int width;

    @Column(name = "risk_lower_bound", nullable = false)
    private int riskLowerBound;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private RiskLevel risk;

    @Column(nullable = false)
    private boolean violated;

    /** Releases whose equations contributed to this cell's interval. */
    @Column(name = "source_releases", nullable = false, length = 600)
    private String sourceReleases;

    @Column(length = 800)
    private String basis;

    @Column(length = 800)
    private String note;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
