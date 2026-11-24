package com.ctrial.disclosure;

import com.ctrial.model.DisclosureCell;
import com.ctrial.model.DisclosureRelease;
import com.ctrial.model.ReleaseRelation;
import com.ctrial.model.Enums.RelationType;
import com.ctrial.model.Enums.RiskLevel;
import com.ctrial.repo.ReleaseRelationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Computes, entirely in the local domain, the containment / intersection /
 * difference relations between published cohorts and flags any pair where the
 * difference isolates a cell below the minimum group.
 *
 * <p>The hardest case in the brief lives here: two tables on the same frozen
 * surface that differ by exactly one centre, both using the same deterministic
 * rounding. Each clears the minimum group alone, yet their difference pins the
 * differing centre. Because rounding is applied identically it cancels in the
 * subtraction, so the analyser treats rounded values as exact and marks the
 * pair <b>SUBTRACTIVE_RISK</b> rather than crediting the rounding.
 */
@Service
public class OverlapAnalyzer {

    private final ReleaseRelationRepo relationRepo;
    private final DisclosureData data;

    public OverlapAnalyzer(ReleaseRelationRepo relationRepo, DisclosureData data) {
        this.relationRepo = relationRepo;
        this.data = data;
    }

    public record PairResult(String a, String b, RelationType type, RiskLevel risk, double overlap,
                             List<String> sharedAtoms, List<String> subtractiveCells, int smallestResidualDenominator,
                             String detail) {
    }

    public Set<String> atoms(ReleaseSnapshot s) {
        Set<String> atoms = new LinkedHashSet<>();
        for (DisclosureCell c : s.cells()) {
            atoms.addAll(AggregationEngine.parseAtomKeys(c.getAtomKeys()));
        }
        return atoms;
    }

    /** Pure comparison for the pre-publish gate (persists nothing). */
    public PairResult compare(ReleaseSnapshot a, ReleaseSnapshot b) {
        Set<String> aAtoms = atoms(a);
        Set<String> bAtoms = atoms(b);
        Set<String> shared = new LinkedHashSet<>(aAtoms);
        shared.retainAll(bAtoms);
        Set<String> union = new LinkedHashSet<>(aAtoms);
        union.addAll(bAtoms);
        double overlap = union.isEmpty() ? 0 : shared.size() / (double) union.size();

        RelationType type = classify(a.release(), b.release(), overlap);
        RiskLevel risk = baseRisk(type, overlap);
        List<String> subtractive = new ArrayList<>();
        int smallestResidual = Integer.MAX_VALUE;

        if (sameSurface(a.release(), b.release()) && sameShape(a.release(), b.release())
                && !shared.isEmpty() && shared.size() < union.size()) {
            Set<String> residualB = new LinkedHashSet<>(bAtoms);
            residualB.removeAll(aAtoms);
            Set<String> residualA = new LinkedHashSet<>(aAtoms);
            residualA.removeAll(bAtoms);
            smallestResidual = smallestResidualDenom(b.release(), residualB);
            smallestResidual = Math.min(smallestResidual, smallestResidualDenom(a.release(), residualA));

            // Structural differencing: two tables of the *same shape* (identical dimensions and
            // centre granularity on the same surface) that differ in exactly one centre. Their
            // cells align, so the difference is computable row-for-row and isolates that centre.
            // Identical deterministic rounding cancels, so it is not protection.
            Set<String> diffAtoms = new LinkedHashSet<>(residualA);
            diffAtoms.addAll(residualB);
            Set<String> diffCentres = new LinkedHashSet<>();
            for (String atom : diffAtoms) {
                diffCentres.add(atom.split("\\|")[0]);
            }
            boolean oneCentreDiff = diffCentres.size() == 1 && !diffAtoms.isEmpty();
            boolean deterministic = sameDeterministicMechanism(a.release(), b.release());

            List<String> small = smallResidualCells(b, residualB);
            small.addAll(smallResidualCells(a, residualA));
            if (!small.isEmpty()) {
                subtractive = small;
            }
            if (oneCentreDiff && deterministic) {
                type = RelationType.SUBTRACTIVE_RISK;
                risk = RiskLevel.HIGH;
                if (subtractive.isEmpty()) {
                    subtractive.add("difference confined to centre " + diffCentres.iterator().next()
                            + " (structural; rounding cancels)");
                }
            } else if (!small.isEmpty()) {
                type = RelationType.SUBTRACTIVE_RISK;
                risk = RiskLevel.HIGH;
            } else if (!residualB.isEmpty() || !residualA.isEmpty()) {
                risk = RiskLevel.MEDIUM;
            }
        }
        int smallest = smallestResidual == Integer.MAX_VALUE ? 0 : smallestResidual;
        String detail = describe(a.release(), b.release(), type, overlap, subtractive, smallest);
        return new PairResult(a.release().getCode(), b.release().getCode(), type, risk, overlap,
                new ArrayList<>(shared), subtractive, smallest, detail);
    }

    private boolean sameDeterministicMechanism(DisclosureRelease a, DisclosureRelease b) {
        com.ctrial.model.Enums.DisclosureMechanism ma = a.getMechanism();
        com.ctrial.model.Enums.DisclosureMechanism mb = b.getMechanism();
        return a.getRoundingUnit() == b.getRoundingUnit()
                && AttackerModel.tolerance(ma) == 0.0 && AttackerModel.tolerance(mb) == 0.0
                && !isRandomised(ma) && !isRandomised(mb);
    }

    private boolean isRandomised(com.ctrial.model.Enums.DisclosureMechanism m) {
        return m == com.ctrial.model.Enums.DisclosureMechanism.LAPLACE
                || m == com.ctrial.model.Enums.DisclosureMechanism.CONSISTENT_RANDOM;
    }

    /** Persist the directed pair edges (used after a successful publish). */
    @Transactional
    public PairResult compareAndPersist(ReleaseSnapshot a, ReleaseSnapshot b) {
        PairResult pr = compare(a, b);
        ReleaseRelation rel = new ReleaseRelation();
        rel.setFromCode(pr.a());
        rel.setToCode(pr.b());
        rel.setType(pr.type());
        rel.setRisk(pr.risk());
        rel.setOverlapFraction(pr.overlap());
        rel.setSharedAtoms(String.join(",", pr.sharedAtoms()));
        rel.setSmallestResidualDenominator(pr.smallestResidualDenominator());
        rel.setDetail(pr.detail());
        rel.setCreatedAt(Instant.now());
        relationRepo.save(rel);
        return pr;
    }

    /** Cells in {@code residual} (atoms unique to this release) whose local denominator is below the floor. */
    private List<String> smallResidualCells(ReleaseSnapshot s, Set<String> residualAtoms) {
        List<String> out = new ArrayList<>();
        if (residualAtoms.isEmpty()) {
            return out;
        }
        int floor = s.release().getSurface().getMinGroupSize();
        int day = s.release().getSurface().getAnalysisDay();
        Map<String, Integer> denoms = data.denominators(day);
        for (DisclosureCell c : s.cells()) {
            Set<String> cellAtoms = AggregationEngine.parseAtomKeys(c.getAtomKeys());
            Set<String> residualPart = new LinkedHashSet<>(cellAtoms);
            residualPart.retainAll(residualAtoms);
            if (residualPart.isEmpty()) {
                continue;
            }
            int residualDenom = residualPart.stream().mapToInt(at -> denoms.getOrDefault(at, 0)).sum();
            if (residualDenom > 0 && residualDenom < floor) {
                out.add(c.getDimKey() + " [residual denom " + residualDenom + "]");
            }
        }
        return out;
    }

    private int smallestResidualDenom(DisclosureRelease r, Set<String> residualAtoms) {
        if (residualAtoms.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        Map<String, Integer> denoms = data.denominators(r.getSurface().getAnalysisDay());
        int min = Integer.MAX_VALUE;
        for (String atom : residualAtoms) {
            min = Math.min(min, denoms.getOrDefault(atom, 0));
        }
        return min;
    }

    private boolean sameSurface(DisclosureRelease a, DisclosureRelease b) {
        return a.getSurface().getDefinitionDigest().equals(b.getSurface().getDefinitionDigest())
                && a.getSurface().getAgeRecode() == b.getSurface().getAgeRecode();
    }

    /** Same shape = identical dimensions and centre granularity, so cells align row-for-row. */
    private boolean sameShape(DisclosureRelease a, DisclosureRelease b) {
        return a.getDimensions().equals(b.getDimensions())
                && a.getCenterGranularity().equals(b.getCenterGranularity());
    }

    public RelationType classify(DisclosureRelease a, DisclosureRelease b, double overlap) {
        if (a.getCode().equals(b.getCode())) {
            return RelationType.DUPLICATE;
        }
        if (!a.getLineage().equals(b.getLineage())) {
            return RelationType.OVERLAPPING;
        }
        if (a.getSurface().getAgeRecode() != b.getSurface().getAgeRecode()) {
            return RelationType.RECODED_DIMENSION;
        }        if (a.getSurface().getDefinitionDigest().equals(b.getSurface().getDefinitionDigest())) {
            return RelationType.SAME_SURFACE;
        }
        if (a.getSurface().getVersion() != b.getSurface().getVersion()
                && a.getDimensions().equals(b.getDimensions())
                && a.getCenterGranularity().equals(b.getCenterGranularity())) {
            return RelationType.SUPERSET_VERSION;
        }
        if (overlap == 0) {
            return RelationType.DISJOINT;
        }
        if (a.getDimensions().equals(b.getDimensions())) {
            return RelationType.NESTED_SUBSET;
        }
        return RelationType.OVERLAPPING;
    }

    private RiskLevel baseRisk(RelationType type, double overlap) {
        return switch (type) {
            case DUPLICATE, SUPERSET_VERSION -> RiskLevel.LOW;
            case RECODED_DIMENSION -> RiskLevel.MEDIUM;
            case OVERLAPPING -> overlap > 0.5 ? RiskLevel.MEDIUM : RiskLevel.LOW;
            case NESTED_SUBSET, SAME_SURFACE -> RiskLevel.MEDIUM;
            default -> RiskLevel.NONE;
        };
    }

    private String describe(DisclosureRelease a, DisclosureRelease b, RelationType type, double overlap,
                            List<String> subtractive, int smallest) {
        String head = a.getCode() + " ⊕ " + b.getCode() + " → " + type + " (overlap "
                + Math.round(overlap * 100) + "%)";
        if (type == RelationType.SUBTRACTIVE_RISK) {
            return head + ": the difference isolates " + subtractive.size()
                    + " residual cell(s); smallest residual denominator " + smallest
                    + " < " + b.getSurface().getMinGroupSize()
                    + ". Deterministic rounding cancels and does NOT protect this pair.";
        }
        return head + ".";
    }

    public List<ReleaseRelation> allRelations() {
        return relationRepo.findAllByOrderByIdAsc();
    }

    public List<PairResult> matrix(List<ReleaseSnapshot> snapshots) {
        List<PairResult> out = new ArrayList<>();
        for (int i = 0; i < snapshots.size(); i++) {
            for (int j = i + 1; j < snapshots.size(); j++) {
                out.add(compare(snapshots.get(i), snapshots.get(j)));
            }
        }
        return out;
    }
}
