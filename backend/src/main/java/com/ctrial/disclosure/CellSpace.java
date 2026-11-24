package com.ctrial.disclosure;

import com.ctrial.model.DisclosureSurface;
import com.ctrial.model.LocalAggregateFact;

import java.util.*;

/**
 * The shared mathematical substrate every disclosure component reasons over.
 *
 * <p>We model the released data as counts of <b>serious cases</b> over a grid of
 * atomic cells {@code (center, ageBand, period)}. An atomic cell is the finest
 * unit the study can ever be sliced into; every published table is a linear
 * sum of these atoms. Working in this basis is what makes overlap, differencing
 * and interval enumeration exact rather than heuristic.
 *
 * <p>The local domain holds the true count per atom ({@link #trueCounts}); the
 * attacker-facing components only ever receive published linear equations over
 * the same atoms and never the true counts.
 */
public final class CellSpace {

    /** All atomic cells in canonical order: center × age × period. */
    public static final List<String> CENTERS = List.of("CN-01", "CN-02", "SG-01");
    public static final List<String> AGES = List.of("A", "B", "C");
    public static final List<String> PERIODS = List.of("P1", "P2", "P3");

    private CellSpace() {
    }

    public record Atom(String center, String age, String period) {
        public String key() {
            return center + "|" + age + "|" + period;
        }
    }

    public static List<Atom> atoms() {
        List<Atom> out = new ArrayList<>();
        for (String c : CENTERS) {
            for (String a : AGES) {
                for (String p : PERIODS) {
                    out.add(new Atom(c, a, p));
                }
            }
        }
        return out;
    }

    public static List<String> atomKeys() {
        return atoms().stream().map(Atom::key).toList();
    }

    /** Centre label at a given granularity. */
    public static String centerLabel(String center, String granularity) {
        if ("FINE".equalsIgnoreCase(granularity)) {
            return center;
        }
        return center.startsWith("CN") ? "CN" : "SG";
    }

    /** Age label at a given recode. BASE keeps A/B/C; AGE_COARSE_V2 folds A+B→"18-64", C→"65+". */
    public static String ageLabel(String age, DisclosureSurface.AgeRecode recode) {
        if (recode == DisclosureSurface.AgeRecode.AGE_COARSE_V2) {
            return "C".equals(age) ? "65+" : "18-64";
        }
        return age;
    }

    /** The set of base ages a recoded label covers. */
    public static Set<String> baseAgesFor(String ageLabel, DisclosureSurface.AgeRecode recode) {
        return recode.labelToBaseAges().getOrDefault(ageLabel, Set.of());
    }

    // ------------------------------------------------------------------
    // True (local-domain) counts
    // ------------------------------------------------------------------

    /**
     * Serious-case count per atom for {@code facts} at {@code analysisDay}.
     * Denominator is separate: how many enrolled members exist per atom (used
     * for the minimum-group test), independent of whether they have an event.
     */
    public static Map<String, Integer> trueCounts(List<LocalAggregateFact> facts, int analysisDay) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : atomKeys()) {
            m.put(k, 0);
        }
        for (LocalAggregateFact f : facts) {
            if (!f.enrolledBy(analysisDay)) {
                continue;
            }
            String key = f.getCenter() + "|" + f.getAgeBand() + "|" + f.getPeriod();
            if (!m.containsKey(key)) {
                continue;
            }
            if (f.seriousBy(analysisDay)) {
                m.merge(key, 1, Integer::sum);
            }
        }
        return m;
    }

    /** Enrolled denominator per atom at {@code analysisDay}. */
    public static Map<String, Integer> denominators(List<LocalAggregateFact> facts, int analysisDay) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (String k : atomKeys()) {
            m.put(k, 0);
        }
        for (LocalAggregateFact f : facts) {
            if (!f.enrolledBy(analysisDay)) {
                continue;
            }
            String key = f.getCenter() + "|" + f.getAgeBand() + "|" + f.getPeriod();
            if (!m.containsKey(key)) {
                continue;
            }
            m.merge(key, 1, Integer::sum);
        }
        return m;
    }

    /** Digests subject membership; never exposes references. */
    public static String membershipDigest(List<LocalAggregateFact> facts, int analysisDay) {
        List<String> refs = facts.stream()
                .filter(f -> f.enrolledBy(analysisDay))
                .map(LocalAggregateFact::getLocalRef)
                .sorted()
                .toList();
        return com.ctrial.common.Text.sha256(String.join("|", refs));
    }
}
