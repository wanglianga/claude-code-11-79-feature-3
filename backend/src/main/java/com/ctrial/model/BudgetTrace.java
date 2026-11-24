package com.ctrial.model;

import com.ctrial.model.Enums.BudgetKind;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One point on the privacy-budget trajectory. A distinct row is written for
 * every request whose loss is charged to the ledger (including repeats and
 * version increments), so the trajectory shows cumulative epsilon over request
 * order. Versions that repeat a cohort charge for the <i>overlap</i>, not as a
 * fresh independent population.
 */
@Entity
@Table(name = "budget_trace", indexes = {
        @Index(name = "idx_budget_trace_lineage", columnList = "lineage")
})
@Getter
@Setter
@NoArgsConstructor
public class BudgetTrace {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String lineage;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "release_code", length = 80)
    private String releaseCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private BudgetKind kind;

    @Column(name = "new_members", nullable = false)
    private int newMembers;

    @Column(name = "repeated_members", nullable = false)
    private int repeatedMembers;

    @Column(name = "overlap_fraction", nullable = false)
    private double overlapFraction;

    @Column(name = "incremental_loss", nullable = false)
    private double incrementalLoss;

    @Column(name = "cumulative_loss", nullable = false)
    private double cumulativeLoss;

    @Column(name = "budget_cap", nullable = false)
    private double budgetCap;

    @Column(nullable = false)
    private boolean allowed;

    @Column(length = 500)
    private String reason;

    @Column(name = "at",
            nullable = false)
    private Instant at;
}
