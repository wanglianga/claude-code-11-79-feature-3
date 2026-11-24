package com.ctrial.disclosure;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.DisclosureSurfaceRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * The single decision point for publishing an aggregate. It runs the full chain
 * — aggregation, budget, overlap/differencing, attack simulation, constraint
 * group analysis — and refuses a request for which <b>any</b> of the following
 * holds, even if the table looks fine on its own:
 * <ul>
 *   <li>a paired table on the same surface would let the difference isolate a
 *       cell below the floor (SUBTRACTIVE_RISK);</li>
 *   <li>the combined release set pins a cell, or lets a suppressed cell be
 *       recovered with a public total (simulation BLOCKED / below floor);</li>
 *   <li>the cumulative budget on the face would exceed its cap.</li>
 * </ul>
 * Every request — published or refused — is written to the ledger.
 */
@Service
public class PublishGate {

    private final DisclosureSurfaceRepo surfaceRepo;
    private final AggregationEngine engine;
    private final DisclosureData data;
    private final ReleaseLedger ledger;
    private final BudgetEvaluator budget;
    private final OverlapAnalyzer overlap;
    private final AttackSimulator simulator;
    private final ConstraintTracker tracker;

    public PublishGate(DisclosureSurfaceRepo surfaceRepo, AggregationEngine engine, DisclosureData data,
                       ReleaseLedger ledger, BudgetEvaluator budget, OverlapAnalyzer overlap,
                       AttackSimulator simulator, ConstraintTracker tracker) {
        this.surfaceRepo = surfaceRepo;
        this.engine = engine;
        this.data = data;
        this.ledger = ledger;
        this.budget = budget;
        this.overlap = overlap;
        this.simulator = simulator;
        this.tracker = tracker;
    }

    public record PreviewCell(String dimKey, String label, int trueValue, int denominator, Integer publishedValue,
                              boolean suppressed, String suppressionReason) {
    }

    public record CellIntervalView(String cellKey, double lo, double hi, double width, String risk, boolean leaked) {
    }

    public record Preview(QuerySpec spec, DisclosureSurface surface, boolean blocked, String verdict,
                          String wouldPublishAs, int publishedCells, int suppressedCells,
                          List<PreviewCell> cells, List<CellIntervalView> inferredIntervals,
                          List<String> findings, double projectedLoss, double budgetCap, double cumulativeLoss,
                          String budgetKind, double overlapFraction, List<String> overlapNotes,
                          String rationale, int memberCount, String membershipDigest, String simulationVerdict) {
    }

    public record PublishResult(DisclosureRelease release, Preview preview) {
    }

    /** A candidate table plus everything derived from it, not yet persisted. */
    private record Candidate(QuerySpec spec, DisclosureSurface surface, AggregationEngine.BuiltTable table,
                             Set<String> atoms, List<DisclosureCell> cells) {
    }

    private Candidate build(QuerySpec spec, DisclosureSurface surface) {
        AggregationEngine.BuiltTable table = engine.materialize(spec, surface, data.facts());
        Set<String> atoms = new LinkedHashSet<>();
        List<DisclosureCell> cells = new ArrayList<>();
        for (AggregationEngine.BuiltCell bc : table.cells()) {
            atoms.addAll(bc.atomKeys());
            DisclosureCell c = new DisclosureCell();
            c.setDimKey(bc.dimKey());
            c.setLabel(bc.label());
            c.setTrueValue(bc.trueValue());
            c.setDenominator(bc.denominator());
            c.setPublishedValue(bc.publishedValue());
            c.setSuppressed(bc.suppressed());
            c.setSuppressionReason(bc.suppressionReason());
            c.setAtomKeys(AggregationEngine.atomKeysCsv(bc.atomKeys()));
            cells.add(c);
        }
        return new Candidate(spec, surface, table, atoms, cells);
    }

    private DisclosureRelease candidateRelease(QuerySpec spec, DisclosureSurface surface, AggregationEngine.BuiltTable table) {
        DisclosureRelease r = new DisclosureRelease();
        r.setCode("CANDIDATE");
        r.setSurface(surface);
        r.setLineage(surface.getLineage());
        r.setPurpose(surface.getPurpose());
        r.setDimensions(String.join(",", spec.dimensions()));
        r.setCenterGranularity(spec.centerGranularity());
        r.setFilterCanonical(spec.filterCanonical());
        r.setMetric(spec.metric());
        r.setMinGroupSize(surface.getMinGroupSize());
        r.setMechanism(mechanismOf(spec.mechanism()));
        r.setMemberCount(table.memberCount());
        r.setStatus(ReleaseStatus.RELEASED); // simulate AS IF it were published
        return r;
    }

    @Transactional(readOnly = true)
    public Preview preview(QuerySpec spec, DisclosureSurface surface, double budgetCap) {
        return evaluate(spec, surface, budgetCap);
    }

    @Transactional
    public PublishResult publish(QuerySpec spec, double budgetCap, String actor) {
        DisclosureSurface surface = surfaceRepo.findByCode(spec.surfaceCode())
                .orElseThrow(() -> new IllegalArgumentException("Unknown surface " + spec.surfaceCode()));
        Preview preview = evaluate(spec, surface, budgetCap);
        Candidate cand = build(spec, surface);

        DisclosureRelease r = new DisclosureRelease();
        r.setCode(ledger.nextCode(surface.getLineage()));
        r.setStatus(preview.blocked() ? ReleaseStatus.BLOCKED : ReleaseStatus.RELEASED);
        r.setRoute(routeOf(spec.route()));
        r.setBudgetKind(parseBudgetKind(preview.budgetKind()));
        r.setIncrementalLoss(preview.blocked() ? 0.0 : preview.projectedLoss());
        // preview.cumulativeLoss() is history + this request's would-be loss (BudgetEvaluator.Verdict).
        // A blocked request charges nothing, so its recorded cumulative is the prior history.
        r.setCumulativeLoss(preview.blocked()
                ? Math.max(0.0, round3(preview.cumulativeLoss() - preview.projectedLoss()))
                : preview.cumulativeLoss());
        r.setBudgetCap(budgetCap);
        r.setOverlapFraction(preview.overlapFraction());
        r.setCreatedBy(actor);
        r.setPolicyLabel(preview.blocked() ? "BLOCKED_BY_GATE" : "PUBLISHED");
        r.setResultVersion("v" + surface.getVersion() + "."
                + (ledger.snapshotsOfLineage(surface.getLineage()).size() + 1));
        // If the surface is a recoded version of an earlier one, the release opens a comparability
        // edge rather than reusing the earlier release's budget.
        if (surface.getComparableOf() != null && !surface.getComparableOf().isBlank()) {
            r.setComparableTo(surface.getComparableOf());
        }
        r.setNote(String.join(" | ", preview.findings()));
        DisclosureRelease stored = ledger.persist(spec, surface, cand.table, r);

        if (preview.blocked()) {
            ledger.recordBlocked(stored, String.join(" ; ", preview.findings()));
            return new PublishResult(stored, preview);
        }
        ledger.markReleased(stored);
        postPublish(stored, surface, actor);
        return new PublishResult(stored, preview);
    }

    /**
     * Admit a <b>historical</b> release that predates the gate, so the ledger has
     * real history for the analyser to reason about. It is recorded as RELEASED
     * unconditionally and the full bookkeeping runs; the differencing analyser
     * will flag any risky pair it forms after the fact. Intended for seeding only.
     */
    @Transactional
    public PublishResult admitHistorical(String code, QuerySpec spec, double budgetCap, String actor, String note) {
        DisclosureSurface surface = surfaceRepo.findByCode(spec.surfaceCode()).orElseThrow();
        Candidate cand = build(spec, surface);
        DisclosureRelease r = new DisclosureRelease();
        r.setCode(code);
        r.setStatus(ReleaseStatus.RELEASED);
        r.setRoute(routeOf(spec.route()));
        BudgetEvaluator.Verdict v = budget.evaluate(candidateRelease(spec, surface, cand.table), cand.atoms,
                cand.table.memberCount(), ledger.snapshotsOfLineage(surface.getLineage()), budgetCap);
        r.setBudgetKind(v.kind());
        r.setIncrementalLoss(v.incrementalLoss());
        r.setCumulativeLoss(v.cumulativeLoss());
        r.setBudgetCap(budgetCap);
        r.setCreatedBy(actor);
        r.setPolicyLabel("BASELINE_HISTORICAL");
        r.setResultVersion("v" + surface.getVersion() + "."
                + (ledger.snapshotsOfLineage(surface.getLineage()).size() + 1));
        r.setNote(note);
        DisclosureRelease stored = ledger.persist(spec, surface, cand.table, r);
        ledger.markReleased(stored);
        postPublish(stored, surface, actor);
        return new PublishResult(stored, null);
    }

    private void postPublish(DisclosureRelease stored, DisclosureSurface surface, String actor) {
        ReleaseSnapshot storedSnap = ledger.snapshot(stored);
        List<ReleaseSnapshot> published = ledger.publishedSnapshots().stream()
                .filter(s -> !s.release().getCode().equals(stored.getCode())).toList();
        for (ReleaseSnapshot p : published) {
            overlap.compareAndPersist(p, storedSnap);
        }
        budget.record(stored, budget.evaluate(stored, atomsOf(storedSnap), stored.getMemberCount(),
                ledger.snapshotsOfLineage(stored.getLineage()), stored.getBudgetCap()));

        List<ReleaseSnapshot> group = ledger.snapshotsOfLineage(surface.getLineage());
        tracker.analyze(group, tracker.groupKey(group));

        simulator.runAndSave(ledger.publishedSnapshots(), surface, actor);
    }

    private Preview evaluate(QuerySpec spec, DisclosureSurface surface, double budgetCap) {
        Candidate cand = build(spec, surface);
        AggregationEngine.BuiltTable table = cand.table;
        List<PreviewCell> cells = table.cells().stream()
                .map(c -> new PreviewCell(c.dimKey(), c.label(), c.trueValue(), c.denominator(),
                        c.publishedValue(), c.suppressed(), c.suppressionReason()))
                .toList();

        List<String> findings = new ArrayList<>();

        // 1) Budget on the face — version increments re-charge overlap; recodes open a new edge.
        DisclosureRelease budgetProbe = candidateRelease(spec, surface, table);
        BudgetEvaluator.Verdict budgetVerdict = budget.evaluate(budgetProbe, cand.atoms, table.memberCount(),
                ledger.snapshotsOfLineage(surface.getLineage()), budgetCap);
        if (!budgetVerdict.allowed()) {
            findings.add("BUDGET_EXCEEDED: cumulative loss "
                    + String.format("%.2f", budgetVerdict.cumulativeLoss()) + " would exceed cap " + budgetCap);
        }

        // 2) Overlap / differencing vs the lineage's published history.
        ReleaseSnapshot candSnap = ReleaseSnapshot.of(budgetProbe, cand.cells);
        List<String> overlapNotes = new ArrayList<>();
        double maxOverlap = 0;
        for (ReleaseSnapshot p : ledger.snapshotsOfLineage(surface.getLineage())) {
            OverlapAnalyzer.PairResult pr = overlap.compare(p, candSnap);
            maxOverlap = Math.max(maxOverlap, pr.overlap());
            if (pr.type() == RelationType.SUBTRACTIVE_RISK) {
                findings.add("SUBTRACTIVE_RISK vs " + p.release().getCode() + ": " + pr.detail());
            } else if (pr.risk() == RiskLevel.HIGH) {
                findings.add("HIGH_OVERLAP vs " + p.release().getCode() + ": " + pr.detail());
            }
            overlapNotes.add(pr.detail());
        }

        // 3) Attack simulation AS IF published, scoped to the candidate's OWN suppressed groups.
        //    A request is refused when its own suppressed cells become recoverable from public
        //    information (this table plus the already-published ledger and public totals). Leaks
        //    that a legacy baseline already caused, unrelated to this table, do not block it.
        List<ReleaseSnapshot> asIfPublished = new ArrayList<>(ledger.publishedSnapshots());
        asIfPublished.add(candSnap);
        AttackSimulator.SimResult sim = simulator.dryRunForOwner(asIfPublished, surface, budgetProbe.getCode());
        List<CellIntervalView> intervals = sim.intervals().stream()
                .map(t -> new CellIntervalView(t.cellKey(), t.lo(), t.hi(), t.width(), t.risk().name(), t.leaked()))
                .toList();
        if (sim.blocked()) {
            findings.add("SIMULATION_BLOCKED: a suppressed small group of this request is pinned by "
                    + "public information alone (" + sim.exactCells() + " cell(s))");
        } else if (sim.belowBound() > 0) {
            findings.add("SIMULATION_LEAK: " + sim.belowBound()
                    + " suppressed group(s) of this request become derivable to width ≤ 1 from the combined ledger");
        }

        boolean blocked = !findings.isEmpty();
        String wouldPublishAs = blocked ? "WITHHELD" : ledger.nextCode(surface.getLineage());
        String rationale = blocked
                ? String.join(" ; ", findings)
                : "Every published cell keeps a group of at least " + surface.getMinGroupSize()
                + "; the combined release set leaves every derivable interval at or above the floor, and cumulative "
                + "loss stays within " + budgetCap + ".";
        return new Preview(spec, surface, blocked, blocked ? "BLOCKED" : "WOULD_PUBLISH", wouldPublishAs,
                (int) table.publishedCount(), (int) table.suppressedCount(), cells, intervals, findings,
                budgetVerdict.incrementalLoss(), budgetCap, budgetVerdict.cumulativeLoss(),
                budgetVerdict.kind().name(), maxOverlap, overlapNotes, rationale, table.memberCount(),
                table.membershipDigest(), sim.verdict());
    }

    private Set<String> atomsOf(ReleaseSnapshot s) {
        Set<String> atoms = new LinkedHashSet<>();
        for (DisclosureCell c : s.cells()) {
            atoms.addAll(AggregationEngine.parseAtomKeys(c.getAtomKeys()));
        }
        return atoms;
    }

    private DisclosureMechanism mechanismOf(String mech) {
        return switch (mech.toUpperCase()) {
            case "ROUNDING" -> DisclosureMechanism.ROUNDING;
            case "LAPLACE" -> DisclosureMechanism.LAPLACE;
            case "CONSISTENT_RANDOM" -> DisclosureMechanism.CONSISTENT_RANDOM;
            case "NONE" -> DisclosureMechanism.NONE;
            default -> DisclosureMechanism.SUPPRESSION;
        };
    }

    private DisclosureRoute routeOf(String route) {
        return "RESTRICTED_LATTICE".equalsIgnoreCase(route) ? DisclosureRoute.RESTRICTED_LATTICE
                : DisclosureRoute.OVERLAPPING_LEDGER;
    }

    private BudgetKind parseBudgetKind(String s) {
        try {
            return BudgetKind.valueOf(s);
        } catch (Exception e) {
            return BudgetKind.NEW_QUERY;
        }
    }

    private double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }
}
