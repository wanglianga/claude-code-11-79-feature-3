package com.ctrial.disclosure;

import com.ctrial.model.*;
import com.ctrial.model.Enums.BudgetKind;
import com.ctrial.model.Enums.RiskLevel;
import com.ctrial.repo.BudgetTraceRepo;
import com.ctrial.repo.DisclosureReleaseRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Merges the cumulative disclosure loss of requests on the same or a heavily
 * overlapping analysis face, and distinguishes three cases:
 * <ul>
 *   <li><b>NEW_QUERY</b> — a genuinely new face; charge full loss;</li>
 *   <li><b>NEW_DATA_INCREMENT</b> — a later watermark on the same face adds new
 *       visits but the <i>old table remains accessible</i>. The new version's
 *       overlap with the old cohort is charged again, because the attacker can
 *       difference the versions; the version number is never treated as an
 *       independent population;</li>
 *   <li><b>DUPLICATE_QUERY</b> — same face, same data; a repeat still costs a
 *       little (re-identification via repetition) but not a fresh epsilon.</li>
 * </ul>
 * A change of grouping that stems from a purpose recode is a
 * <b>DEFINITION_CHANGE</b>: it must not silently reuse the old budget; it opens
 * a comparability edge instead.
 */
@Service
public class BudgetEvaluator {

    /** The sensitivity of one count query. Small because each query is one count release. */
    public static final double BASE_EPSILON = 0.20;

    private final DisclosureReleaseRepo releaseRepo;
    private final BudgetTraceRepo traceRepo;

    public BudgetEvaluator(DisclosureReleaseRepo releaseRepo, BudgetTraceRepo traceRepo) {
        this.releaseRepo = releaseRepo;
        this.traceRepo = traceRepo;
    }

    public record Verdict(BudgetKind kind, double incrementalLoss, double cumulativeLoss, double cap,
                          int newMembers, int repeatedMembers, double overlapFraction, boolean allowed,
                          String reason, String comparedTo) {
    }

    /**
     * Evaluate the loss of a prospective release against the published history of
     * its lineage. {@code candidateAtoms} is the candidate's atom coverage;
     * {@code priorSnapshots} are the published snapshots on the same lineage.
     */
    @Transactional
    public Verdict evaluate(DisclosureRelease candidate, Set<String> candidateAtoms, int candidateMembers,
                            List<ReleaseSnapshot> priorSnapshots, double cap) {
        List<ReleaseSnapshot> history = priorSnapshots.stream()
                .filter(s -> !s.release().getCode().equals(candidate.getCode()))
                .toList();

        double cumulative = history.stream().mapToDouble(s -> s.release().getIncrementalLoss()).sum();
        if (history.isEmpty()) {
            double loss = BASE_EPSILON;
            return new Verdict(BudgetKind.NEW_QUERY, loss, cumulative + loss, cap,
                    candidateMembers, 0, 0, cumulative + loss <= cap,
                    "first release on this lineage", null);
        }

        ReleaseSnapshot best = null;
        double bestOverlap = -1;
        // A duplicate face match wins over raw overlap: a repeat of an existing table must be
        // recognised as such even if some other release happens to overlap it slightly more.
        ReleaseSnapshot exactFace = null;
        for (ReleaseSnapshot s : history) {
            Set<String> shared = new LinkedHashSet<>(candidateAtoms);
            shared.retainAll(atomsOf(s));
            Set<String> union = new LinkedHashSet<>(candidateAtoms);
            union.addAll(atomsOf(s));
            double overlap = union.isEmpty() ? 0 : shared.size() / (double) union.size();
            if (isSameFace(candidate, s.release())) {
                exactFace = s;
                bestOverlap = overlap;
                break;
            }
            if (overlap > bestOverlap) {
                bestOverlap = overlap;
                best = s;
            }
        }
        if (exactFace != null) {
            best = exactFace;
        }
        DisclosureRelease bestRel = best.release();
        int repeatedMembers = (int) Math.round(bestOverlap * Math.min(candidateMembers, bestRel.getMemberCount()));
        int newMembers = Math.max(0, candidateMembers - repeatedMembers);

        BudgetKind kind = classify(candidate, bestRel);
        double loss;
        String reason;
        switch (kind) {
            case DUPLICATE_QUERY -> {
                loss = BASE_EPSILON * 0.1;
                reason = "repeat of " + bestRel.getCode() + " on the same face; small repetition cost, not fresh budget";
            }
            case NEW_DATA_INCREMENT -> {
                loss = BASE_EPSILON * (1.0 + bestOverlap);
                reason = "later watermark on " + bestRel.getCode() + "'s face; the old version stays accessible, so "
                        + Math.round(bestOverlap * 100) + "% overlapping members are re-charged (version ≠ new population)";
            }
            case DEFINITION_CHANGE -> {
                loss = BASE_EPSILON * (1.5 + bestOverlap);
                reason = "re-coded dimension vs " + bestRel.getCode()
                        + "; a comparability edge is opened and the old budget is not silently reused";
            }
            default -> {
                loss = BASE_EPSILON * (1.0 + bestOverlap);
                reason = "new grouping on an overlapping face; loss scaled by overlap with " + bestRel.getCode();
            }
        }
        double newCumulative = cumulative + loss;
        boolean allowed = newCumulative <= cap + 1e-9;
        return new Verdict(kind, round(loss), round(newCumulative), cap, newMembers, repeatedMembers, bestOverlap,
                allowed, reason, bestRel.getCode());
    }

    private Set<String> atomsOf(ReleaseSnapshot s) {
        Set<String> atoms = new LinkedHashSet<>();
        for (com.ctrial.model.DisclosureCell c : s.cells()) {
            atoms.addAll(AggregationEngine.parseAtomKeys(c.getAtomKeys()));
        }
        return atoms;
    }

    private boolean isSameFace(DisclosureRelease candidate, DisclosureRelease prior) {
        // A true duplicate shares the *same* surface (hence analysis day) and shape. A later
        // watermark on the same lineage is not a duplicate — it is an increment (see classify).
        return prior.getSurface().getCode().equals(candidate.getSurface().getCode())
                && prior.getDimensions().equals(candidate.getDimensions())
                && prior.getCenterGranularity().equals(candidate.getCenterGranularity())
                && prior.getFilterCanonical().equals(candidate.getFilterCanonical())
                && prior.getMetric().equals(candidate.getMetric());
    }

    private BudgetKind classify(DisclosureRelease candidate, DisclosureRelease prior) {
        if (!prior.getSurface().getLineage().equals(candidate.getSurface().getLineage())) {
            return BudgetKind.DEFINITION_CHANGE;
        }
        if (prior.getSurface().getAgeRecode() != candidate.getSurface().getAgeRecode()) {
            return BudgetKind.DEFINITION_CHANGE;
        }
        boolean sameShape = prior.getDimensions().equals(candidate.getDimensions())
                && prior.getCenterGranularity().equals(candidate.getCenterGranularity())
                && prior.getFilterCanonical().equals(candidate.getFilterCanonical())
                && prior.getSurface().getCode().equals(candidate.getSurface().getCode());
        if (sameShape) {
            return BudgetKind.DUPLICATE_QUERY;
        }
        if (candidate.getSurface().getAnalysisDay() > prior.getSurface().getAnalysisDay()
                && prior.getDimensions().equals(candidate.getDimensions())
                && prior.getCenterGranularity().equals(candidate.getCenterGranularity())) {
            return BudgetKind.NEW_DATA_INCREMENT;
        }
        if (!prior.getDimensions().equals(candidate.getDimensions())) {
            return BudgetKind.NEW_QUERY;
        }
        return BudgetKind.NEW_QUERY;
    }

    private double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    @Transactional
    public BudgetTrace record(DisclosureRelease release, Verdict v) {
        int seq = (int) traceRepo.countByLineage(release.getLineage()) + 1;
        BudgetTrace t = new BudgetTrace();
        t.setLineage(release.getLineage());
        t.setSeq(seq);
        t.setReleaseCode(release.getCode());
        t.setKind(v.kind());
        t.setNewMembers(v.newMembers());
        t.setRepeatedMembers(v.repeatedMembers());
        t.setOverlapFraction(v.overlapFraction());
        t.setIncrementalLoss(v.incrementalLoss());
        t.setCumulativeLoss(v.cumulativeLoss());
        t.setBudgetCap(v.cap());
        t.setAllowed(v.allowed());
        t.setReason(v.reason());
        t.setAt(Instant.now());
        return traceRepo.save(t);
    }

    @Transactional(readOnly = true)
    public List<BudgetTrace> traces() {
        return traceRepo.findAllByOrderByLineageAscSeqAscIdAsc();
    }

    @Transactional(readOnly = true)
    public double cumulativeLoss(String lineage) {
        return releaseRepo.findByLineageOrderByRequestedAtAscIdAsc(lineage).stream()
                .filter(DisclosureRelease::isPublished)
                .mapToDouble(DisclosureRelease::getIncrementalLoss).sum();
    }

    /** The risk of a request that would exceed the cap or reuse a recoded budget illegally. */
    public RiskLevel budgetRisk(Verdict v) {
        if (!v.allowed()) {
            return RiskLevel.BLOCKED;
        }
        double used = v.cap() == 0 ? 0 : v.cumulativeLoss() / v.cap();
        if (used > 0.9) {
            return RiskLevel.HIGH;
        }
        if (used > 0.7) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }
}
