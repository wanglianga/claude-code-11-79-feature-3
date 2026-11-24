package com.ctrial.disclosure;

import com.ctrial.model.DisclosureSurface;
import com.ctrial.model.LocalAggregateFact;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * Builds and publishes the aggregate table for a {@link QuerySpec}.
 *
 * <p>Two phases:
 * <ol>
 *   <li><b>build</b> — roll atomic cells up into the requested grouping and
 *       attach each cell's enrolled denominator and the atoms it covers;</li>
 *   <li><b>publish</b> — apply the minimum-group suppression and then, only to
 *       cells that cleared it, the rounding / perturbation mechanism. Suppression
 *       is primary: no mechanism ever rescues a cell below the minimum group.</li>
 * </ol>
 *
 * <p>Deterministic rounding publishes a coarser integer; the differencing
 * analyser and the attack simulator deliberately treat that integer as an
 * <b>exact</b> linear constraint (tolerance 0). We refuse to credit
 * deterministic rounding as protection. Randomised mechanisms (LAPLACE,
 * CONSISTENT_RANDOM) publish a noisy integer and advertise a real tolerance.
 */
@Component
public class AggregationEngine {

    public record RawCell(String dimKey, String label, int trueValue, int denominator, Set<String> atomKeys) {
    }

    /** A cell after suppression and mechanism application. */
    public record BuiltCell(String dimKey, String label, int trueValue, int denominator, Integer publishedValue,
                            boolean suppressed, String suppressionReason, Set<String> atomKeys) {
    }

    public record BuiltTable(QuerySpec spec, DisclosureSurface surface, List<BuiltCell> cells,
                             int memberCount, String membershipDigest) {
        public long publishedCount() {
            return cells.stream().filter(c -> !c.suppressed()).count();
        }

        public long suppressedCount() {
            return cells.stream().filter(BuiltCell::suppressed).count();
        }
    }

    /** Phase 1: raw grouped cells with denominators and atom coverage. */
    public List<RawCell> build(QuerySpec spec, DisclosureSurface surface, List<LocalAggregateFact> facts) {
        Map<String, Integer> counts = CellSpace.trueCounts(facts, surface.getAnalysisDay());
        Map<String, Integer> denoms = CellSpace.denominators(facts, surface.getAnalysisDay());
        List<String> centers = spec.effectiveCenters(surface);
        List<String> periods = spec.effectivePeriods(surface);

        Map<String, RawCell> cells = new LinkedHashMap<>();
        for (String center : centers) {
            for (String age : surface.baseAgeUniverse()) {
                for (String period : periods) {
                    String dimKey = dimKey(spec, surface, center, age, period);
                    String label = labelOf(spec, surface, center, age, period);
                    Set<String> atoms = new LinkedHashSet<>();
                    int addCount = 0;
                    int addDenom = 0;
                    for (CellSpace.Atom atom : CellSpace.atoms()) {
                        if (!atom.center().equals(center) || !atom.age().equals(age) || !atom.period().equals(period)) {
                            continue;
                        }
                        atoms.add(atom.key());
                        addCount += counts.getOrDefault(atom.key(), 0);
                        addDenom += denoms.getOrDefault(atom.key(), 0);
                    }
                    RawCell cur = cells.get(dimKey);
                    if (cur == null) {
                        cells.put(dimKey, new RawCell(dimKey, label, addCount, addDenom, atoms));
                    } else {
                        Set<String> merged = new LinkedHashSet<>(cur.atomKeys());
                        merged.addAll(atoms);
                        cells.put(dimKey, new RawCell(dimKey, label, cur.trueValue() + addCount,
                                cur.denominator() + addDenom, merged));
                    }
                }
            }
        }
        List<RawCell> ordered = new ArrayList<>(cells.values());
        ordered.sort(Comparator.comparing(RawCell::dimKey));
        return ordered;
    }

    /** Phase 2: suppression + mechanism. */
    public BuiltTable materialize(QuerySpec spec, DisclosureSurface surface, List<LocalAggregateFact> facts) {
        List<RawCell> raw = build(spec, surface, facts);
        List<BuiltCell> out = new ArrayList<>();
        for (RawCell c : raw) {
            if (c.denominator() < surface.getMinGroupSize()) {
                out.add(new BuiltCell(c.dimKey(), c.label(), c.trueValue(), c.denominator(), null, true,
                        "BELOW_MIN_GROUP (" + c.denominator() + "<" + surface.getMinGroupSize() + ")",
                        c.atomKeys()));
            } else {
                Integer published = applyMechanism(spec, surface, c);
                out.add(new BuiltCell(c.dimKey(), c.label(), c.trueValue(), c.denominator(), published, false,
                        null, c.atomKeys()));
            }
        }
        int members = (int) facts.stream().filter(f -> f.enrolledBy(surface.getAnalysisDay()))
                .filter(f -> spec.effectiveCenters(surface).contains(f.getCenter()))
                .filter(f -> spec.effectivePeriods(surface).contains(f.getPeriod()))
                .count();
        String digest = CellSpace.membershipDigest(facts.stream()
                .filter(f -> spec.effectiveCenters(surface).contains(f.getCenter()))
                .filter(f -> spec.effectivePeriods(surface).contains(f.getPeriod()))
                .toList(), surface.getAnalysisDay());
        return new BuiltTable(spec, surface, out, members, digest);
    }

    private Integer applyMechanism(QuerySpec spec, DisclosureSurface surface, RawCell c) {
        return switch (spec.mechanism()) {
            case "ROUNDING" -> DisclosureMath.roundToUnit(c.trueValue(), Math.max(1, surface.getRoundingUnit()));
            case "LAPLACE" -> {
                double noise = DisclosureMath.laplace("lap|" + surface.getLineage() + "|"
                        + surface.getAnalysisDay() + "|" + c.dimKey(), 1.0);
                yield Math.max(0, (int) Math.round(c.trueValue() + noise));
            }
            case "CONSISTENT_RANDOM" -> {
                // Correlated across versions: seed omits the analysis day so the same cell keeps its noise.
                double noise = DisclosureMath.laplace("cr|" + surface.getLineage() + "|" + c.dimKey(), 0.5);
                yield Math.max(0, (int) Math.round(c.trueValue() + noise));
            }
            default -> c.trueValue(); // SUPPRESSION / NONE publish the raw count
        };
    }

    private String dimKey(QuerySpec spec, DisclosureSurface surface, String center, String age, String period) {
        List<String> parts = new ArrayList<>();
        for (String dim : spec.dimensions()) {
            switch (dim) {
                case "AGE_BAND" -> parts.add("AGE_BAND=" + CellSpace.ageLabel(age, surface.getAgeRecode()));
                case "CENTER_GROUP" -> parts.add("CENTER_GROUP=" + CellSpace.centerLabel(center, spec.centerGranularity()));
                case "PERIOD" -> parts.add("PERIOD=" + period);
                default -> parts.add(dim + "=?");
            }
        }
        parts.sort(String::compareTo);
        return String.join(";", parts);
    }

    private String labelOf(QuerySpec spec, DisclosureSurface surface, String center, String age, String period) {
        List<String> parts = new ArrayList<>();
        for (String dim : spec.dimensions()) {
            switch (dim) {
                case "AGE_BAND" -> parts.add("age " + CellSpace.ageLabel(age, surface.getAgeRecode()));
                case "CENTER_GROUP" -> parts.add("centre " + CellSpace.centerLabel(center, spec.centerGranularity()));
                case "PERIOD" -> parts.add("period " + period);
                default -> parts.add(dim);
            }
        }
        return String.join(" · ", parts);
    }

    public static String atomKeysCsv(Set<String> atoms) {
        return String.join(",", atoms);
    }

    public static Set<String> parseAtomKeys(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String t : csv.split(",")) {
            if (!t.isBlank()) {
                out.add(t.trim());
            }
        }
        return out;
    }
}
