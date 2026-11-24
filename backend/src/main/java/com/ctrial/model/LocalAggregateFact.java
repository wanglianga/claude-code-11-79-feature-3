package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * <b>Local-domain</b> aggregate cohort member. One row per de-identified safety
 * subject used to build summary tables. This is the only place subject-level
 * membership exists, and it never leaves the local domain: the release ledger
 * stores aggregate cells plus a membership digest, never these references.
 *
 * <p>Time is modelled by two nullable day numbers so a later watermark can add
 * information about an <i>existing</i> member without pretending the version
 * is a fresh population:
 * <ul>
 *   <li>{@code enrolledDay} — when the subject entered the safety denominator;</li>
 *   <li>{@code seriousReportedDay} — when a serious AE was reported for them
 *       (null = none reported yet).</li>
 * </ul>
 * A table at analysis day N counts a subject only if {@code enrolledDay ≤ N};
 * they count as a serious case only if {@code seriousReportedDay ≤ N}. So the
 * day-60 version has the same people as day-45 but a few more serious events
 * reported after the day-45 watermark.
 */
@Entity
@Table(name = "local_aggregate_fact", indexes = {
        @Index(name = "idx_laf_dims", columnList = "age_band,center,period")
})
@Getter
@Setter
@NoArgsConstructor
public class LocalAggregateFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Local-only opaque reference. Never persisted in the release ledger or projected by any API. */
    @Column(name = "local_ref", nullable = false, unique = true)
    private String localRef;

    /** Base age band: A (18-39), B (40-64), C (65+). */
    @Column(name = "age_band", nullable = false, length = 8)
    private String ageBand;

    /** Site code, e.g. CN-01. */
    @Column(nullable = false, length = 12)
    private String center;

    /** Reporting period bucket P1 (day ≤20) / P2 (day ≤40) / P3 (day >40). */
    @Column(nullable = false, length = 4)
    private String period;

    @Column(name = "enrolled_day", nullable = false)
    private int enrolledDay;

    @Column(name = "serious_reported_day")
    private Integer seriousReportedDay;

    public boolean enrolledBy(int analysisDay) {
        return enrolledDay <= analysisDay;
    }

    public boolean seriousBy(int analysisDay) {
        return seriousReportedDay != null && seriousReportedDay <= analysisDay;
    }

    public String centerGroup() {
        return center.startsWith("CN") ? "CN" : "SG";
    }
}
