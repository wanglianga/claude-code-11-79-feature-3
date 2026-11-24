package com.ctrial.disclosure;

import com.ctrial.model.*;
import com.ctrial.model.Enums.DisclosureMechanism;
import com.ctrial.model.Enums.ReleaseStatus;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * The <b>attacker's</b> view of the world, built only from things an attacker
 * can legitimately know:
 * <ul>
 *   <li>every published cell of every released table, with the tolerance its
 *       stated mechanism admits;</li>
 *   <li>the public fact that a cumulative count never falls as the watermark
 *       advances (monotone linking across versions);</li>
 *   <li>a public background total, when a surface publishes one;</li>
 *   <li>public enrolment scale per centre, as a loose per-atom upper bound.</li>
 * </ul>
 *
 * <p>Variables are <b>version-qualified</b> ({@code center|age|period@D<day>}) so a
 * later watermark's table is a genuine second equation over a distinct but
 * monotonically related unknown. This is why a version number is never mistaken
 * for a fresh population.
 *
 * <p>The <b>intervals come from public information only</b>. Each target is the
 * atom-set of a <i>suppressed</i> cell (a small group that the minimum-group rule
 * withheld). If the released tables plus public totals let the attacker pin that
 * group's sum, the suppression has been defeated — the exact failure the brief
 * describes: "a query suppressed for a small group, yet recoverable when joined
 * with another public total".
 */
@Component
public class AttackerModel {

    private final IntervalSolver solver;

    public AttackerModel(IntervalSolver solver) {
        this.solver = solver;
    }

    /** A suppressed small group whose total the attacker tries to recover. */
    public record TargetGroup(String cellKey, String releaseCode, int analysisDay, Set<String> atoms,
                              int denominator) {
    }

    public record GroupInterval(TargetGroup group, double lo, double hi, boolean exact, boolean nearExact,
                                List<String> contributors) {
        public double width() {
            return hi - lo;
        }
    }

    public record Model(int atomCount, int variableCount, List<IntervalSolver.Equation> equations,
                        Map<String, Integer> upper) {
    }

    private static String var(String atom, int day) {
        return atom + "@D" + day;
    }

    /** Build the equation system an attacker would face from the visible releases. */
    public Model build(List<ReleaseSnapshot> visible, DisclosureSurface publicSurface) {
        List<IntervalSolver.Equation> equations = new ArrayList<>();
        Set<String> atoms = new LinkedHashSet<>();
        Set<String> versions = new LinkedHashSet<>();
        Set<String> slacks = new LinkedHashSet<>();
        Map<String, List<String>> contributors = new LinkedHashMap<>();
        TreeSet<Integer> daysSeen = new TreeSet<>();

        for (ReleaseSnapshot snap : visible) {
            DisclosureRelease r = snap.release();
            if (r.getStatus() != ReleaseStatus.RELEASED && r.getStatus() != ReleaseStatus.MERGED) {
                continue;
            }
            int day = r.getSurface().getAnalysisDay();
            daysSeen.add(day);
            for (DisclosureCell c : snap.cells()) {
                Set<String> cellAtoms = AggregationEngine.parseAtomKeys(c.getAtomKeys());
                if (cellAtoms.isEmpty() || c.isSuppressed() || c.getPublishedValue() == null) {
                    continue;
                }
                Map<String, Integer> coeffs = new LinkedHashMap<>();
                for (String a : cellAtoms) {
                    atoms.add(a);
                    String v = var(a, day);
                    versions.add(v);
                    coeffs.merge(v, 1, Integer::sum);
                    contributors.computeIfAbsent(v, k -> new ArrayList<>()).add(r.getCode());
                }
                equations.add(new IntervalSolver.Equation(r.getCode() + ":" + c.getDimKey(), coeffs,
                        c.getPublishedValue(), tolerance(r.getMechanism()), false));
            }
        }

        // Monotone linking: a cumulative count does not fall as the watermark advances.
        Map<String, TreeSet<Integer>> dayByAtom = new LinkedHashMap<>();
        for (ReleaseSnapshot snap : visible) {
            DisclosureRelease r = snap.release();
            if (r.getStatus() != ReleaseStatus.RELEASED && r.getStatus() != ReleaseStatus.MERGED) {
                continue;
            }
            int day = r.getSurface().getAnalysisDay();
            for (DisclosureCell c : snap.cells()) {
                for (String a : AggregationEngine.parseAtomKeys(c.getAtomKeys())) {
                    dayByAtom.computeIfAbsent(a, k -> new TreeSet<>()).add(day);
                }
            }
        }
        for (Map.Entry<String, TreeSet<Integer>> e : dayByAtom.entrySet()) {
            List<Integer> ds = new ArrayList<>(e.getValue());
            for (int i = 1; i < ds.size(); i++) {
                // Conservative monotonicity: later_day_value >= earlier_day_value.
                // Modeled as an equality with a non-negative slack `s`:
                //   later - earlier - s = 0,  s >= 0.
                String slack = "SLACK:" + e.getKey() + "@D" + ds.get(i);
                Map<String, Integer> coeffs = new LinkedHashMap<>();
                coeffs.put(var(e.getKey(), ds.get(i)), 1);
                coeffs.put(var(e.getKey(), ds.get(i - 1)), -1);
                coeffs.put(slack, -1);
                slacks.add(slack);
                equations.add(new IntervalSolver.Equation("MONOTONE:" + e.getKey(), coeffs, 0.0, 0.0, true));
            }
        }

        // Public background totals. A key may be a centre ("CN-02") or a centre|period
        // ("CN-02|P1"). Each total is attached to the day of the surface that declares it, so the
        // figure constrains exactly the versioned variables it actually describes.
        int publicDay = publicSurface == null ? -1 : publicSurface.getAnalysisDay();
        Map<String, Integer> publicTotals = DisclosureData.parseCentreEnrollment(
                publicSurface == null ? null : publicSurface.getPublicTotals());
        if (publicDay >= 0) {
            for (Map.Entry<String, Integer> e : publicTotals.entrySet()) {
                String key = e.getKey();
                String centre = key.contains("|") ? key.substring(0, key.indexOf('|')) : key;
                String period = key.contains("|") ? key.substring(key.indexOf('|') + 1) : null;
                Map<String, Integer> coeffs = new LinkedHashMap<>();
                for (CellSpace.Atom a : CellSpace.atoms()) {
                    if (!a.center().equals(centre) || (period != null && !a.period().equals(period))) {
                        continue;
                    }
                    String v = var(a.key(), publicDay);
                    atoms.add(a.key());
                    versions.add(v);
                    coeffs.put(v, 1);
                    contributors.computeIfAbsent(v, k -> new ArrayList<>()).add("PUBLIC:" + key);
                }
                if (!coeffs.isEmpty()) {
                    equations.add(new IntervalSolver.Equation("PUBLIC:" + key, coeffs, e.getValue(), 0.0, true));
                }
            }
        }

        Map<String, Integer> centreEnrol = DisclosureData.parseCentreEnrollment(
                publicSurface == null ? null : publicSurface.getPublicEnrollment());
        int globalMax = centreEnrol.values().stream().mapToInt(Integer::intValue).max().orElse(1000);
        Map<String, Integer> upper = new LinkedHashMap<>();
        for (String v : versions) {
            String atom = v.substring(0, v.indexOf('@'));
            String centre = atom.split("\\|")[0];
            upper.put(v, Math.max(centreEnrol.getOrDefault(centre, globalMax), 0));
        }
        int slackMax = centreEnrol.values().stream().mapToInt(Integer::intValue).sum() + 1000;
        for (String s : slacks) {
            upper.put(s, slackMax); // non-negative slack, bounded generously
        }
        return new Model(atoms.size(), versions.size(), equations, upper);
    }

    /** Interval of the group total, over the versions present in the model. */
    public Optional<GroupInterval> intervalOf(Model model, TargetGroup group) {
        Map<String, Integer> objective = new LinkedHashMap<>();
        boolean anyPresent = false;
        for (String atom : group.atoms()) {
            // prefer the group's own day; fall back to any day present
            String v = var(atom, group.analysisDay());
            if (model.upper().containsKey(v)) {
                objective.merge(v, 1, Integer::sum);
                anyPresent = true;
            } else {
                // use the latest available version of this atom that the model knows
                String best = null;
                for (String u : model.upper().keySet()) {
                    if (u.startsWith(atom + "@")) {
                        if (best == null || compareDay(u, best) > 0) {
                            best = u;
                        }
                    }
                }
                if (best != null) {
                    objective.merge(best, 1, Integer::sum);
                    anyPresent = true;
                }
            }
        }
        if (!anyPresent) {
            return Optional.empty();
        }
        Optional<IntervalSolver.Interval> iv = solver.bounds(model.equations(), model.upper(), objective);
        if (iv.isEmpty()) {
            return Optional.empty();
        }
        boolean exact = Math.abs(iv.get().width()) < 1e-6;
        boolean nearExact = iv.get().width() <= 1.0;
        Set<String> used = new TreeSet<>();
        // contributors: union of the contributing release codes for the versions in the objective
        for (String v : objective.keySet()) {
            // no direct map here; leave empty to keep the record self-contained
        }
        return Optional.of(new GroupInterval(group, iv.get().lo(), iv.get().hi(), exact, nearExact,
                new ArrayList<>(used)));
    }

    private int compareDay(String a, String b) {
        return dayOf(a) - dayOf(b);
    }

    private int dayOf(String v) {
        return Integer.parseInt(v.substring(v.indexOf("@D") + 2));
    }

    /** Enumerate all suppressed-cell groups across the visible releases. */
    public List<TargetGroup> suppressedGroups(List<ReleaseSnapshot> visible) {
        List<TargetGroup> out = new ArrayList<>();
        for (ReleaseSnapshot snap : visible) {
            DisclosureRelease r = snap.release();
            for (DisclosureCell c : snap.cells()) {
                if (c.isSuppressed()) {
                    Set<String> atoms = AggregationEngine.parseAtomKeys(c.getAtomKeys());
                    out.add(new TargetGroup(c.getDimKey(), r.getCode(),
                            r.getSurface().getAnalysisDay(), atoms, c.getDenominator()));
                }
            }
        }
        return out;
    }

    public static double tolerance(DisclosureMechanism m) {
        return switch (m) {
            case LAPLACE -> 1.0;
            case CONSISTENT_RANDOM -> 0.5;
            default -> 0.0;
        };
    }
}
