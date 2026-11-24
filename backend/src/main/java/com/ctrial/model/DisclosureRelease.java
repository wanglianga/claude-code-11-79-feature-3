package com.ctrial.model;

import com.ctrial.model.Enums.*;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One entry in the <b>aggregate release ledger</b>. It freezes everything a
 * regulator needs to reason about a published summary table after the fact:
 * the analysis surface, the grouping dimensions, the canonical filter, the
 * denominator scope, which cells were suppressed, the rounding / perturbation
 * mechanism, the result version and the budget it consumed.
 *
 * <p>It deliberately does <b>not</b> store subject-level members. The only
 * membership artefact is {@code membershipDigest}, a one-way hash of the local
 * member references, used to prove two cohorts were identical without being
 * able to enumerate them.
 */
@Entity
@Table(name = "disclosure_release", uniqueConstraints = {
        @UniqueConstraint(name = "uq_release_code", columnNames = {"code"})
}, indexes = {
        @Index(name = "idx_release_surface", columnList = "surface_id"),
        @Index(name = "idx_release_lineage", columnList = "lineage")
})
@Getter
@Setter
@NoArgsConstructor
public class DisclosureRelease {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "surface_id")
    private DisclosureSurface surface;

    @Column(nullable = false)
    private String lineage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    /** Grouping dimensions, sorted CSV, e.g. "AGE_BAND,CENTER_GROUP,PERIOD". */
    @Column(nullable = false, length = 120)
    private String dimensions;

    /** COARSE (CN / SG) or FINE (CN-01 / CN-02 / SG-01) centre grouping. */
    @Column(name = "center_granularity", nullable = false, length = 10)
    private String centerGranularity;

    @Column(name = "filter_canonical", nullable = false, length = 400)
    private String filterCanonical;

    @Column(name = "denominator_scope", nullable = false, length = 300)
    private String denominatorScope;

    @Column(nullable = false, length = 40)
    private String metric;

    @Column(name = "metric_definition", length = 400)
    private String metricDefinition;

    @Column(name = "min_group_size", nullable = false)
    private int minGroupSize;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisclosureMechanism mechanism;

    @Column(name = "mechanism_detail", length = 400)
    private String mechanismDetail;

    @Column(name = "rounding_unit", nullable = false)
    private int roundingUnit;

    @Column(name = "result_version", nullable = false, length = 40)
    private String resultVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReleaseStatus status;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DisclosureRoute route;

    @Column(name = "membership_digest", length = 64)
    private String membershipDigest;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    @Column(name = "published_cells", nullable = false)
    private int publishedCells;

    @Column(name = "suppressed_cells", nullable = false)
    private int suppressedCells;

    @Enumerated(EnumType.STRING)
    @Column(name = "budget_kind", nullable = false)
    private BudgetKind budgetKind;

    @Column(name = "incremental_loss", nullable = false)
    private double incrementalLoss;

    @Column(name = "cumulative_loss", nullable = false)
    private double cumulativeLoss;

    @Column(name = "budget_cap", nullable = false)
    private double budgetCap;

    @Column(name = "overlap_fraction", nullable = false)
    private double overlapFraction;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "created_by", nullable = false)
    private String createdBy;

    @Column(name = "blocked_reason", length = 1200)
    private String blockedReason;

    @Column(name = "policy_label", length = 80)
    private String policyLabel;

    /** For a version that repeats a prior release's grouping. */
    @Column(name = "parent_code", length = 80)
    private String parentCode;

    /** For a recoded surface release: the earlier release it is comparable to but must not reuse budget with. */
    @Column(name = "comparable_to", length = 80)
    private String comparableTo;

    @Column(length = 1000)
    private String note;

    public boolean isPublished() {
        return status == ReleaseStatus.RELEASED || status == ReleaseStatus.MERGED;
    }
}
