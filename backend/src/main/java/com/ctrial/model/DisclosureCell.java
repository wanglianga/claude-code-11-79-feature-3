package com.ctrial.model;

import com.ctrial.model.Enums.RiskLevel;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One cell of a published (or blocked/deferred) aggregate table. Carries the
 * grouping tuple, the published value, whether it was suppressed and why, and
 * the denominator that drove the minimum-group test.
 *
 * <p>{@code trueValue} is the aggregate count the local engine computed. It is
 * an aggregate, not a subject identifier, and is kept for audit so a reviewer
 * can confirm the published value really was derived and suppressed correctly.
 */
@Entity
@Table(name = "disclosure_cell", indexes = {
        @Index(name = "idx_cell_release", columnList = "release_id")
})
@Getter
@Setter
@NoArgsConstructor
public class DisclosureCell {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "release_id")
    private DisclosureRelease release;

    /** Canonical dimension tuple, e.g. "AGE_BAND=A;CENTER_GROUP=CN;PERIOD=P1". */
    @Column(name = "dim_key", nullable = false, length = 200)
    private String dimKey;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "true_value", nullable = false)
    private int trueValue;

    @Column(nullable = false)
    private int denominator;

    @Column(name = "published_value")
    private Integer publishedValue;

    @Column(nullable = false)
    private boolean suppressed;

    @Column(name = "suppression_reason", length = 200)
    private String suppressionReason;

    /**
     * Canonical atomic cells this grouping tuple rolls up, CSV of
     * {@code center|age|period}. This is dimension metadata, never a subject
     * reference; it is what lets overlap / differencing be computed exactly.
     */
    @Column(name = "atom_keys", length = 2000)
    private String atomKeys;

    @Enumerated(EnumType.STRING)
    @Column(name = "risk_level", nullable = false)
    private RiskLevel riskLevel = RiskLevel.NONE;
}
