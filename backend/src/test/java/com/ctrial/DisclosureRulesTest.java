package com.ctrial;

import com.ctrial.disclosure.*;
import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rule-level tests for the aggregate disclosure-control layer. Each test names
 * the requirement it proves and exercises the real services against the seeded
 * cohort, so the numbers are the numbers the API serves.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DisclosureRulesTest {

    @Autowired DisclosureSurfaceRepo surfaceRepo;
    @Autowired DisclosureReleaseRepo releaseRepo;
    @Autowired DisclosureCellRepo cellRepo;
    @Autowired PublishGate gate;
    @Autowired OverlapAnalyzer overlap;
    @Autowired AttackSimulator simulator;
    @Autowired BudgetEvaluator budget;
    @Autowired DisclosureData data;
    @Autowired AggregationEngine engine;

    private DisclosureSurface v1() {
        return surfaceRepo.findByCode("SURF-SAFETY-V1").orElseThrow();
    }

    private QuerySpec spec(String surface, List<String> dims, String gran, List<String> centres) {
        return new QuerySpec(surface, dims, gran, centres, null, "SERIOUS_CASE_COUNT", "SUPPRESSION",
                "OVERLAPPING_LEDGER", "test request");
    }

    private ReleaseSnapshot snap(DisclosureRelease r) {
        return ReleaseSnapshot.of(r, cellRepo.findByReleaseIdOrderByDimKeyAsc(r.getId()));
    }

    @Test
    void periodPooledCellsClearFloorButPeriodCellsForTheTinyGroupAreSuppressed() {
        // Age×centre (period pooled): CN-02 age C has 5 enrolled -> clears the floor.
        QuerySpec pooled = spec("SURF-SAFETY-V1", List.of("AGE_BAND", "CENTER_GROUP"), "FINE", null);
        AggregationEngine.BuiltTable tp = engine.materialize(pooled, v1(), data.facts());
        var cn01a = tp.cells().stream()
                .filter(c -> c.dimKey().contains("CENTER_GROUP=CN-01") && c.dimKey().contains("AGE_BAND=A"))
                .findFirst().orElseThrow();
        assertThat(cn01a.suppressed()).isFalse();
        var cn02cPooled = tp.cells().stream()
                .filter(c -> c.dimKey().contains("CENTER_GROUP=CN-02") && c.dimKey().contains("AGE_BAND=C"))
                .findFirst().orElseThrow();
        assertThat(cn02cPooled.denominator()).isEqualTo(5);
        assertThat(cn02cPooled.suppressed()).isFalse();

        // Age×centre×PERIOD: each CN-02 age-C period cell has denominator 2/2/1 -> all suppressed.
        QuerySpec period = spec("SURF-SAFETY-V1", List.of("AGE_BAND", "CENTER_GROUP", "PERIOD"), "FINE", null);
        AggregationEngine.BuiltTable tpr = engine.materialize(period, v1(), data.facts());
        var cn02cPeriods = tpr.cells().stream()
                .filter(c -> c.dimKey().contains("CENTER_GROUP=CN-02") && c.dimKey().contains("AGE_BAND=C"))
                .toList();
        assertThat(cn02cPeriods).hasSize(3);
        assertThat(cn02cPeriods).allMatch(AggregationEngine.BuiltCell::suppressed);
        assertThat(cn02cPeriods).allMatch(c -> c.suppressionReason().contains("BELOW_MIN_GROUP"));
    }

    @Test
    void suppressedSmallGroupPlusPublicTotalIsRecoveredAndBlocked() {
        // The seeded legacy period-level table has suppressed CN-02 age-C cells; the public
        // CN-02|P1 total (=1) pins the P1 cell exactly. The simulator must expose it.
        DisclosureRelease legacy = releaseRepo.findByCode("REL-BASE-LEGACY").orElseThrow();
        AttackSimulator.SimResult sim = simulator.dryRun(
                List.of(snap(legacy)), v1());
        var p1 = sim.intervals().stream()
                .filter(i -> i.cellKey().contains("CENTER_GROUP=CN-02") && i.cellKey().contains("AGE_BAND=C")
                        && i.cellKey().contains("PERIOD=P1"))
                .findFirst();
        assertThat(p1).isPresent();
        assertThat(p1.get().leaked()).isTrue();
        assertThat(p1.get().lo()).isEqualTo(p1.get().hi());
        assertThat(sim.blocked()).isTrue();

        // And a fresh period-level request on the same surface must be refused by the gate.
        QuerySpec period = spec("SURF-SAFETY-V1", List.of("AGE_BAND", "CENTER_GROUP", "PERIOD"), "FINE", null);
        PublishGate.Preview preview = gate.preview(period, v1(), 100.0);
        assertThat(preview.blocked()).isTrue();
        assertThat(preview.findings()).anyMatch(f -> f.startsWith("SIMULATION_"));
    }

    @Test
    void oneCentreDifferenceUnderDeterministicRoundingIsSubtractiveRisk() {
        // Two coarse tables: all three sites, vs the two that exclude CN-02. Same surface,
        // same deterministic mechanism (suppression) -> the difference isolates CN-02 and its
        // tiny age-C group. Rounding does not protect it.
        QuerySpec all = spec("SURF-SAFETY-V1", List.of("AGE_BAND", "CENTER_GROUP"), "COARSE",
                List.of("CN-01", "CN-02", "SG-01"));
        QuerySpec without = spec("SURF-SAFETY-V1", List.of("AGE_BAND", "CENTER_GROUP"), "COARSE",
                List.of("CN-01", "SG-01"));
        gate.admitHistorical("BASE-ALL", all, 100.0, "test", "baseline: all centres");
        gate.admitHistorical("BASE-NO-CN02", without, 100.0, "test", "baseline: without CN-02");

        DisclosureRelease a = releaseRepo.findByCode("BASE-ALL").orElseThrow();
        DisclosureRelease b = releaseRepo.findByCode("BASE-NO-CN02").orElseThrow();
        OverlapAnalyzer.PairResult pr = overlap.compare(snap(a), snap(b));
        assertThat(pr.type()).isEqualTo(RelationType.SUBTRACTIVE_RISK);
        assertThat(pr.risk()).isEqualTo(RiskLevel.HIGH);
        assertThat(pr.detail()).containsIgnoringCase("rounding");
    }

    @Test
    void laterWatermarkChargesOverlapAsIncrementNotFreshPopulation() {
        // A FINE age×centre table on V1 is already published (REL-BASE-001). Requesting the same
        // shape on V2 (later watermark, same people) must be an increment, not a fresh population.
        DisclosureSurface v2 = surfaceRepo.findByCode("SURF-SAFETY-V2").orElseThrow();
        QuerySpec q2 = spec("SURF-SAFETY-V2", List.of("AGE_BAND", "CENTER_GROUP"), "FINE", null);
        DisclosureRelease stub = releaseStub(v2, q2);
        BudgetEvaluator.Verdict v = budget.evaluate(stub, atomsOf(q2), 133,
                ledgerSnapshots("SAFETY-COHORT-2026"), 100.0);

        assertThat(v.kind()).isEqualTo(BudgetKind.NEW_DATA_INCREMENT);
        assertThat(v.repeatedMembers()).isGreaterThan(0);
        assertThat(v.overlapFraction()).isGreaterThan(0.9);
        assertThat(v.reason()).containsIgnoringCase("version");
    }

    @Test
    void recodedSurfaceOpensComparabilityEdgeNotBudgetReuse() {
        DisclosureSurface v2 = surfaceRepo.findByCode("SURF-SAFETY-V2").orElseThrow();
        DisclosureSurface v3 = surfaceRepo.findByCode("SURF-SAFETY-V3").orElseThrow();
        assertThat(v3.getAgeRecode()).isEqualTo(DisclosureSurface.AgeRecode.AGE_COARSE_V2);
        assertThat(v3.getComparableOf()).isEqualTo("SURF-SAFETY-V2");
        assertThat(v3.getComparabilityNote()).isNotBlank();
        assertThat(v2.getAgeRecode()).isNotEqualTo(v3.getAgeRecode());
    }

    // ---- helpers ----

    private ReleaseSnapshot buildTransient(QuerySpec s, PublishGate.Preview p) {
        DisclosureRelease r = new DisclosureRelease();
        r.setCode("CANDIDATE");
        r.setSurface(v1());
        r.setLineage(v1().getLineage());
        r.setDimensions(String.join(",", s.dimensions()));
        r.setCenterGranularity(s.centerGranularity());
        r.setFilterCanonical(s.filterCanonical());
        r.setMechanism(DisclosureMechanism.SUPPRESSION);
        r.setStatus(ReleaseStatus.RELEASED);
        r.setMemberCount(p.memberCount());
        List<DisclosureCell> cells = new ArrayList<>();
        for (PublishGate.PreviewCell pc : p.cells()) {
            DisclosureCell c = new DisclosureCell();
            // reconstruct atoms for the dim key using the same grouping the engine used
            c.setDimKey(pc.dimKey());
            c.setLabel(pc.label());
            c.setTrueValue(pc.trueValue());
            c.setDenominator(pc.denominator());
            c.setPublishedValue(pc.publishedValue());
            c.setSuppressed(pc.suppressed());
            c.setSuppressionReason(pc.suppressionReason());
            cells.add(c);
        }
        // derive atom coverage per cell exactly as the engine would
        Map<String, Set<String>> atomsByKey = engineAtoms(s);
        for (DisclosureCell c : cells) {
            c.setAtomKeys(AggregationEngine.atomKeysCsv(atomsByKey.getOrDefault(c.getDimKey(), Set.of())));
        }
        return ReleaseSnapshot.of(r, cells);
    }

    private Map<String, Set<String>> engineAtoms(QuerySpec s) {
        AggregationEngine.BuiltTable t = engine.materialize(s, v1(), data.facts());
        Map<String, Set<String>> m = new LinkedHashMap<>();
        for (var bc : t.cells()) {
            m.put(bc.dimKey(), bc.atomKeys());
        }
        return m;
    }

    private Set<String> atomsOf(QuerySpec q) {
        DisclosureSurface s = surfaceRepo.findByCode(q.surfaceCode()).orElseThrow();
        AggregationEngine.BuiltTable t = engine.materialize(q, s, data.facts());
        Set<String> atoms = new LinkedHashSet<>();
        for (var c : t.cells()) atoms.addAll(c.atomKeys());
        return atoms;
    }

    private List<ReleaseSnapshot> ledgerSnapshots(String lineage) {
        return releaseRepo.findByLineageOrderByRequestedAtAscIdAsc(lineage).stream()
                .filter(DisclosureRelease::isPublished)
                .map(this::snap)
                .toList();
    }

    private DisclosureRelease releaseStub(DisclosureSurface s, QuerySpec q) {
        DisclosureRelease r = new DisclosureRelease();
        r.setCode("PROBE");
        r.setSurface(s);
        r.setLineage(s.getLineage());
        r.setDimensions(String.join(",", q.dimensions()));
        r.setCenterGranularity(q.centerGranularity());
        r.setFilterCanonical(q.filterCanonical());
        return r;
    }
}
