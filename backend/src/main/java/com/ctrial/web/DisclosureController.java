package com.ctrial.web;

import com.ctrial.disclosure.*;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Aggregate disclosure-control API: the release ledger, the release relation
 * graph, the overlap/differencing analyser, the attack simulator, the budget
 * trajectory and the policy sandbox. Every list the attacker-facing endpoints
 * return is derived from published tables and public background only.
 */
@RestController
@RequestMapping("/api/disclosure")
public class DisclosureController {

    private final ReleaseLedger ledger;
    private final OverlapAnalyzer overlap;
    private final AttackSimulator simulator;
    private final ConstraintTracker tracker;
    private final BudgetEvaluator budget;
    private final PolicySandbox sandbox;
    private final PublishGate gate;
    private final DisclosureReleaseRepo releaseRepo;
    private final DisclosureCellRepo cellRepo;
    private final DisclosureSurfaceRepo surfaceRepo;
    private final AuditService audit;

    public DisclosureController(ReleaseLedger ledger, OverlapAnalyzer overlap, AttackSimulator simulator,
                                ConstraintTracker tracker, BudgetEvaluator budget, PolicySandbox sandbox,
                                PublishGate gate, DisclosureReleaseRepo releaseRepo, DisclosureCellRepo cellRepo,
                                DisclosureSurfaceRepo surfaceRepo, AuditService audit) {
        this.ledger = ledger;
        this.overlap = overlap;
        this.simulator = simulator;
        this.tracker = tracker;
        this.budget = budget;
        this.sandbox = sandbox;
        this.gate = gate;
        this.releaseRepo = releaseRepo;
        this.cellRepo = cellRepo;
        this.surfaceRepo = surfaceRepo;
        this.audit = audit;
    }

    private String actor(Authentication auth) {
        return auth == null ? "anonymous" : auth.getName();
    }

    // ---------------- surfaces ----------------

    @GetMapping("/surfaces")
    public List<Map<String, Object>> surfaces() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DisclosureSurface s : ledger.surfaces()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.getCode());
            m.put("lineage", s.getLineage());
            m.put("version", s.getVersion());
            m.put("purpose", s.getPurpose());
            m.put("analysisDay", s.getAnalysisDay());
            m.put("ageRecode", s.getAgeRecode());
            m.put("status", s.getStatus());
            m.put("minGroupSize", s.getMinGroupSize());
            m.put("roundingUnit", s.getRoundingUnit());
            m.put("publicEnrollment", s.getPublicEnrollment());
            m.put("publicTotals", s.getPublicTotals());
            m.put("definitionDigest", s.getDefinitionDigest());
            m.put("comparableOf", s.getComparableOf());
            m.put("comparabilityNote", s.getComparabilityNote());
            m.put("note", s.getNote());
            out.add(m);
        }
        return out;
    }

    // ---------------- release ledger ----------------

    @GetMapping("/releases")
    public List<Map<String, Object>> releases() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DisclosureRelease r : ledger.all()) {
            out.add(releaseSummary(r));
        }
        return out;
    }

    @GetMapping("/releases/{code}")
    public ResponseEntity<?> release(@PathVariable String code) {
        Optional<DisclosureRelease> r = ledger.find(code);
        if (r.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        Map<String, Object> m = releaseSummary(r.get());
        List<Map<String, Object>> cells = new ArrayList<>();
        for (DisclosureCell c : ledger.cellsOf(r.get())) {
            Map<String, Object> cm = new LinkedHashMap<>();
            cm.put("dimKey", c.getDimKey());
            cm.put("label", c.getLabel());
            cm.put("trueValue", c.getTrueValue());
            cm.put("denominator", c.getDenominator());
            cm.put("publishedValue", c.getPublishedValue());
            cm.put("suppressed", c.isSuppressed());
            cm.put("suppressionReason", c.getSuppressionReason());
            cm.put("atomCount", AggregationEngine.parseAtomKeys(c.getAtomKeys()).size());
            cells.add(cm);
        }
        m.put("cells", cells);
        m.put("relations", ledger.relations().stream()
                .filter(rel -> rel.getFromCode().equals(code) || rel.getToCode().equals(code))
                .toList());
        return ResponseEntity.ok(m);
    }

    private Map<String, Object> releaseSummary(DisclosureRelease r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.getCode());
        m.put("surface", r.getSurface().getCode());
        m.put("lineage", r.getLineage());
        m.put("purpose", r.getPurpose());
        m.put("dimensions", r.getDimensions());
        m.put("centerGranularity", r.getCenterGranularity());
        m.put("filterCanonical", r.getFilterCanonical());
        m.put("denominatorScope", r.getDenominatorScope());
        m.put("metric", r.getMetric());
        m.put("minGroupSize", r.getMinGroupSize());
        m.put("mechanism", r.getMechanism());
        m.put("mechanismDetail", r.getMechanismDetail());
        m.put("roundingUnit", r.getRoundingUnit());
        m.put("resultVersion", r.getResultVersion());
        m.put("status", r.getStatus());
        m.put("route", r.getRoute());
        m.put("memberCount", r.getMemberCount());
        m.put("publishedCells", r.getPublishedCells());
        m.put("suppressedCells", r.getSuppressedCells());
        m.put("membershipDigest", r.getMembershipDigest());
        m.put("budgetKind", r.getBudgetKind());
        m.put("incrementalLoss", r.getIncrementalLoss());
        m.put("cumulativeLoss", r.getCumulativeLoss());
        m.put("budgetCap", r.getBudgetCap());
        m.put("overlapFraction", r.getOverlapFraction());
        m.put("requestedAt", r.getRequestedAt());
        m.put("createdBy", r.getCreatedBy());
        m.put("blockedReason", r.getBlockedReason());
        m.put("policyLabel", r.getPolicyLabel());
        m.put("comparableTo", r.getComparableTo());
        m.put("note", r.getNote());
        return m;
    }

    // ---------------- relation graph ----------------

    @GetMapping("/graph")
    public Map<String, Object> graph() {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (DisclosureRelease r : ledger.all()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("code", r.getCode());
            n.put("kind", "RELEASE");
            n.put("surface", r.getSurface().getCode());
            n.put("status", r.getStatus());
            n.put("dimensions", r.getDimensions());
            n.put("publishedCells", r.getPublishedCells());
            n.put("suppressedCells", r.getSuppressedCells());
            n.put("day", r.getSurface().getAnalysisDay());
            nodes.add(n);
        }
        // Surface nodes so comparability edges resolve to a known node.
        for (DisclosureSurface s : ledger.surfaces()) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("code", s.getCode());
            n.put("kind", "SURFACE");
            n.put("status", s.getStatus());
            n.put("day", s.getAnalysisDay());
            n.put("version", s.getVersion());
            nodes.add(n);
        }
        List<Map<String, Object>> edges = new ArrayList<>();
        for (ReleaseRelation rel : ledger.relations()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("from", rel.getFromCode());
            e.put("to", rel.getToCode());
            e.put("type", rel.getType());
            e.put("risk", rel.getRisk());
            e.put("overlap", rel.getOverlapFraction());
            e.put("detail", rel.getDetail());
            e.put("atomsOverlap", rel.getSharedAtoms() == null ? 0 : rel.getSharedAtoms().split(",").length);
            edges.add(e);
        }
        // comparability edges (recoded surface) are a distinct edge type
        for (DisclosureSurface s : ledger.surfaces()) {
            if (s.getComparableOf() != null && !s.getComparableOf().isBlank()) {
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("from", s.getComparableOf());
                e.put("to", s.getCode());
                e.put("type", "COMPARABILITY");
                e.put("risk", RiskLevel.MEDIUM);
                e.put("detail", s.getComparabilityNote());
                edges.add(e);
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("nodes", nodes);
        m.put("edges", edges);
        return m;
    }

    // ---------------- overlap / differencing analyser ----------------

    @GetMapping("/overlap")
    public Map<String, Object> overlapAnalysis(@RequestParam(required = false) String lineage) {
        List<DisclosureRelease> rels = lineage == null || lineage.isBlank()
                ? ledger.published() : releaseRepo.findByLineageOrderByRequestedAtAscIdAsc(lineage);
        List<ReleaseSnapshot> snaps = new ArrayList<>();
        for (DisclosureRelease r : rels) {
            if (r.isPublished()) {
                snaps.add(ReleaseSnapshot.of(r, ledger.cellsOf(r)));
            }
        }
        List<OverlapAnalyzer.PairResult> pairs = overlap.matrix(snaps);
        List<Map<String, Object>> pairViews = new ArrayList<>();
        int subtractive = 0;
        for (OverlapAnalyzer.PairResult pr : pairs) {
            if (pr.type() == RelationType.SUBTRACTIVE_RISK) {
                subtractive++;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("a", pr.a());
            m.put("b", pr.b());
            m.put("type", pr.type());
            m.put("risk", pr.risk());
            m.put("overlap", pr.overlap());
            m.put("sharedAtoms", pr.sharedAtoms().size());
            m.put("subtractiveCells", pr.subtractiveCells());
            m.put("smallestResidualDenominator", pr.smallestResidualDenominator());
            m.put("detail", pr.detail());
            pairViews.add(m);
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        for (ConstraintFinding f : tracker.findings()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("groupKey", f.getGroupKey());
            m.put("cellKey", f.getCellKey());
            m.put("lowerBound", f.getLowerBound());
            m.put("upperBound", f.getUpperBound());
            m.put("width", f.getWidth());
            m.put("denominator", f.getRiskLowerBound());
            m.put("risk", f.getRisk());
            m.put("basis", f.getBasis());
            m.put("sourceReleases", f.getSourceReleases());
            findings.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("pairs", pairViews);
        out.put("subtractivePairs", subtractive);
        out.put("constraintFindings", findings);
        out.put("count", pairs.size());
        return out;
    }

    // ---------------- attack simulator ----------------

    @GetMapping("/simulations")
    public List<AttackSimulation> simulations() {
        return simulator.history();
    }

    public record SimulateRequest(String surfaceCode) {
    }

    @PostMapping("/simulate")
    public Map<String, Object> simulate(@RequestBody(required = false) SimulateRequest req, Authentication auth) {
        DisclosureSurface surface = resolveSurface(req == null ? null : req.surfaceCode());
        AttackSimulator.SimResult result = simulator.runAndSave(ledger.publishedSnapshots(), surface, actor(auth));
        audit.record(actor(auth), "ATTACK_SIMULATED", "AttackSimulation", result.code(),
                result.verdict() + " (" + result.targets() + " suppressed groups, " + result.equations() + " eqs)");
        return simView(result);
    }

    private Map<String, Object> simView(AttackSimulator.SimResult r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("code", r.code());
        m.put("scope", r.scope());
        m.put("knowledge", r.knowledge());
        m.put("variables", r.variables());
        m.put("equations", r.equations());
        m.put("targets", r.targets());
        m.put("belowBound", r.belowBound());
        m.put("exactCells", r.exactCells());
        m.put("blocked", r.blocked());
        m.put("verdict", r.verdict());
        m.put("notes", r.notes());
        m.put("intervals", r.intervals().stream().map(t -> {
            Map<String, Object> i = new LinkedHashMap<>();
            i.put("cellKey", t.cellKey());
            i.put("analysisDay", t.analysisDay());
            i.put("lo", t.lo());
            i.put("hi", t.hi());
            i.put("width", t.width());
            i.put("risk", t.risk());
            i.put("leaked", t.leaked());
            i.put("denominator", t.denominator());
            return i;
        }).toList());
        return m;
    }

    // ---------------- budget trajectory ----------------

    @GetMapping("/budget")
    public Map<String, Object> budget() {
        List<Map<String, Object>> traces = new ArrayList<>();
        for (BudgetTrace t : budget.traces()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("lineage", t.getLineage());
            m.put("seq", t.getSeq());
            m.put("releaseCode", t.getReleaseCode());
            m.put("kind", t.getKind());
            m.put("newMembers", t.getNewMembers());
            m.put("repeatedMembers", t.getRepeatedMembers());
            m.put("overlapFraction", t.getOverlapFraction());
            m.put("incrementalLoss", t.getIncrementalLoss());
            m.put("cumulativeLoss", t.getCumulativeLoss());
            m.put("budgetCap", t.getBudgetCap());
            m.put("allowed", t.isAllowed());
            m.put("reason", t.getReason());
            m.put("at", t.getAt());
            traces.add(m);
        }
        Map<String, Double> perLineage = new LinkedHashMap<>();
        for (DisclosureRelease r : ledger.published()) {
            perLineage.merge(r.getLineage(), r.getIncrementalLoss(), Double::sum);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traces", traces);
        m.put("cumulativeByLineage", perLineage);
        return m;
    }

    // ---------------- policy sandbox ----------------

    public record SandboxRequest(String surfaceCode, List<String> dimensions, String centerGranularity,
                                 List<String> centers, List<String> periods, String mechanism, Double budgetCap) {
    }

    @PostMapping("/sandbox")
    public PolicySandbox.SandboxReport sandbox(@RequestBody SandboxRequest req) {
        DisclosureSurface surface = resolveSurface(req.surfaceCode());
        QuerySpec spec = new QuerySpec(surface.getCode(),
                req.dimensions() == null ? List.of("AGE_BAND", "CENTER_GROUP") : req.dimensions(),
                req.centerGranularity() == null ? "COARSE" : req.centerGranularity(),
                req.centers(), req.periods(), "SERIOUS_CASE_COUNT",
                req.mechanism() == null ? "SUPPRESSION" : req.mechanism(),
                "OVERLAPPING_LEDGER", "Sandbox request on " + surface.getCode());
        double cap = req.budgetCap() == null ? 1.0 : req.budgetCap();
        return sandbox.evaluate(spec, surface, cap);
    }

    // ---------------- publish (the gate) ----------------

    public record PublishRequest(String surfaceCode, List<String> dimensions, String centerGranularity,
                                 List<String> centers, List<String> periods, String mechanism, String route,
                                 Double budgetCap, String label) {
    }

    @PostMapping("/publish")
    public Map<String, Object> publish(@RequestBody PublishRequest req, Authentication auth) {
        DisclosureSurface surface = resolveSurface(req.surfaceCode());
        QuerySpec spec = new QuerySpec(surface.getCode(),
                req.dimensions() == null ? List.of("AGE_BAND", "CENTER_GROUP") : req.dimensions(),
                req.centerGranularity() == null ? "COARSE" : req.centerGranularity(),
                req.centers(), req.periods(), "SERIOUS_CASE_COUNT",
                req.mechanism() == null ? "SUPPRESSION" : req.mechanism(),
                req.route() == null ? "OVERLAPPING_LEDGER" : req.route(),
                req.label() == null ? "Aggregate request" : req.label());
        double cap = req.budgetCap() == null ? 1.0 : req.budgetCap();
        PublishGate.PublishResult res = gate.publish(spec, cap, actor(auth));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("release", releaseSummary(res.release()));
        m.put("blocked", res.release().getStatus() == ReleaseStatus.BLOCKED);
        m.put("preview", previewView(res.preview()));
        return m;
    }

    public record PreviewRequest(String surfaceCode, List<String> dimensions, String centerGranularity,
                                 List<String> centers, List<String> periods, String mechanism,
                                 Double budgetCap) {
    }

    @PostMapping("/preview")
    public Map<String, Object> preview(@RequestBody PreviewRequest req) {
        DisclosureSurface surface = resolveSurface(req.surfaceCode());
        QuerySpec spec = new QuerySpec(surface.getCode(),
                req.dimensions() == null ? List.of("AGE_BAND", "CENTER_GROUP") : req.dimensions(),
                req.centerGranularity() == null ? "COARSE" : req.centerGranularity(),
                req.centers(), req.periods(), "SERIOUS_CASE_COUNT",
                req.mechanism() == null ? "SUPPRESSION" : req.mechanism(),
                "OVERLAPPING_LEDGER", "Preview request on " + surface.getCode());
        double cap = req.budgetCap() == null ? 1.0 : req.budgetCap();
        return previewView(gate.preview(spec, surface, cap));
    }

    private Map<String, Object> previewView(PublishGate.Preview p) {
        if (p == null) {
            return Map.of();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("blocked", p.blocked());
        m.put("verdict", p.verdict());
        m.put("wouldPublishAs", p.wouldPublishAs());
        m.put("publishedCells", p.publishedCells());
        m.put("suppressedCells", p.suppressedCells());
        m.put("memberCount", p.memberCount());
        m.put("membershipDigest", p.membershipDigest());
        m.put("budgetKind", p.budgetKind());
        m.put("projectedLoss", p.projectedLoss());
        m.put("cumulativeLoss", p.cumulativeLoss());
        m.put("budgetCap", p.budgetCap());
        m.put("overlapFraction", p.overlapFraction());
        m.put("findings", p.findings());
        m.put("overlapNotes", p.overlapNotes());
        m.put("rationale", p.rationale());
        m.put("simulationVerdict", p.simulationVerdict());
        m.put("cells", p.cells());
        m.put("inferredIntervals", p.inferredIntervals().stream().map(i -> {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("cellKey", i.cellKey());
            x.put("lo", i.lo());
            x.put("hi", i.hi());
            x.put("width", i.width());
            x.put("risk", i.risk());
            x.put("leaked", i.leaked());
            return x;
        }).toList());
        return m;
    }

    private DisclosureSurface resolveSurface(String code) {
        if (code != null && !code.isBlank()) {
            return surfaceRepo.findByCode(code)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown surface " + code));
        }
        return surfaceRepo.findAllByOrderByLineageAscVersionAsc().stream()
                .filter(s -> s.getStatus() == SurfaceState.ACTIVE)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No surface seeded"));
    }
}
