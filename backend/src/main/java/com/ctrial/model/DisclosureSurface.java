package com.ctrial.model;

import com.ctrial.model.Enums.Purpose;
import com.ctrial.model.Enums.SurfaceState;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A <b>frozen aggregate analysis surface</b>. Every published summary table is
 * computed against exactly one surface: purpose, protocol versions, field
 * groups, observation window, analysis-day watermark, cohort filter and the
 * age-band recoding all fixed at freeze time. Two tables that differ only in
 * their grouping are still on the same surface — which is why differencing
 * them is a real risk even when each one on its own respects the minimum group
 * size.
 *
 * <p>The surface never references subjects. It is the definitional anchor the
 * release ledger points at so that a later reader can tell whether two
 * releases are comparable, nested, a superset version, or recoded.
 */
@Entity
@Table(name = "disclosure_surface", uniqueConstraints = {
        @UniqueConstraint(name = "uq_surface_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class DisclosureSurface {

    /** Age-band scheme used by this surface. Recoding is a purpose-change event. */
    public enum AgeRecode {
        /** Three bands: A (18-39), B (40-64), C (65+). */
        BASE,
        /** Two bands after a purpose change: "18-64" = {A,B}, "65+" = {C}. */
        AGE_COARSE_V2;

        public Map<String, Set<String>> labelToBaseAges() {
            Map<String, Set<String>> m = new LinkedHashMap<>();
            if (this == BASE) {
                m.put("A", Set.of("A"));
                m.put("B", Set.of("B"));
                m.put("C", Set.of("C"));
            } else {
                m.put("18-64", new LinkedHashSet<>(Set.of("A", "B")));
                m.put("65+", new LinkedHashSet<>(Set.of("C")));
            }
            return m;
        }

        public Set<String> labels() {
            return labelToBaseAges().keySet();
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    /** Stable identity across versions; two surfaces with the same lineage are versions of one analysis face. */
    @Column(nullable = false)
    private String lineage;

    @Column(nullable = false)
    private int version;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Purpose purpose;

    @Column(name = "protocol_versions", nullable = false, length = 300)
    private String protocolVersions;

    @Column(name = "field_groups", nullable = false, length = 400)
    private String fieldGroups;

    @Column(name = "observation_from_day", nullable = false)
    private int observationFromDay;

    @Column(name = "observation_to_day", nullable = false)
    private int observationToDay;

    /** Analysis-day watermark this surface was frozen at (the newest visit folded in). */
    @Column(name = "analysis_day", nullable = false)
    private int analysisDay;

    @Enumerated(EnumType.STRING)
    @Column(name = "age_recode", nullable = false)
    private AgeRecode ageRecode = AgeRecode.BASE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SurfaceState status = SurfaceState.ACTIVE;

    /** Canonical cohort filter of the denominator, e.g. "centerGroup=ALL;periods=P1,P2". */
    @Column(name = "cohort_filter", nullable = false, length = 400)
    private String cohortFilter;

    /** Full canonical definition; hashing this is how the ledger proves two requests shared one surface. */
    @Column(nullable = false, length = 1200)
    private String definition;

    @Column(name = "definition_digest", nullable = false, length = 64)
    private String definitionDigest;

    @Column(name = "rounding_policy", nullable = false, length = 60)
    private String roundingPolicy;

    /** Rounding unit used when the mechanism is ROUNDING. */
    @Column(name = "rounding_unit", nullable = false)
    private int roundingUnit = 5;

    @Column(name = "min_group_size", nullable = false)
    private int minGroupSize = 5;

    /**
     * Publicly known enrollment per centre, e.g. "CN-01=54;CN-02=52;SG-01=51".
     * This is the only "public enrollment scale" the attacker model is allowed
     * to use as an upper bound; it never contains per-cell detail.
     */
    @Column(name = "public_enrollment", length = 300)
    private String publicEnrollment;

    /**
     * Publicly known serious-case totals per centre, e.g. "CN-02=10" — a
     * registry / annual-report figure. Optional; when present the simulator may
     * combine it with our tables, which is exactly how a suppressed cell plus a
     * public total becomes reconstructable.
     */
    @Column(name = "public_totals", length = 300)
    private String publicTotals;

    @Column(name = "frozen_at", nullable = false)
    private Instant frozenAt;

    /** When this surface is a recoding of an earlier one, points at that surface for the comparability edge. */
    @Column(name = "comparable_of")
    private String comparableOf;

    @Column(name = "comparability_note", length = 800)
    private String comparabilityNote;

    @Column(length = 1000)
    private String note;

    public Set<String> periodUniverse() {
        Set<String> s = new LinkedHashSet<>();
        for (int d = observationFromDay; d <= observationToDay; d++) {
            s.add("P" + periodOf(d));
        }
        return s;
    }

    public static int periodOf(int day) {
        if (day <= 20) {
            return 1;
        }
        return day <= 40 ? 2 : 3;
    }

    public Set<String> baseAgeUniverse() {
        return new LinkedHashSet<>(Set.of("A", "B", "C"));
    }

    public Set<String> centerUniverse() {
        return new LinkedHashSet<>(Set.of("CN-01", "CN-02", "SG-01"));
    }
}
