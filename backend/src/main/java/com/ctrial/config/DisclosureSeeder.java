package com.ctrial.config;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Seeds the <b>aggregate disclosure-control</b> scenario, layered on the same
 * study. It creates:
 * <ul>
 *   <li>a local-domain cohort of de-identified safety subjects with enrollment
 *       day and serious-AE reporting day, so calendar versions can add events to
 *       <i>existing</i> members;</li>
 *   <li>two frozen analysis surfaces — v1 at analysis day 45 and v2 at day 60
 *       (same lineage, later watermark), plus a recoded v3 surface whose banding
 *       changed under a sponsor purpose change;</li>
 *   <li>a small set of baseline releases so the overlap/differencing analyser and
 *       the budget trajectory have real history to reason about.</li>
 * </ul>
 *
 * <p>The cohort is built so that:
 * <ul>
 *   <li>most (centre, age) cells have at least 5 enrolled members and are
 *       publishable;</li>
 *   <li>CN-02 age C has exactly 3 enrolled members across all periods, so any
 *       cell that isolates it is below the minimum group and is suppressed;</li>
 *   <li>the public registry publishes CN-02/period figures, which is what lets a
 *       suppressed CN-02/P1 cell be reconstructed when joined with a total.</li>
 * </ul>
 */
@Component
@Order(20)
public class DisclosureSeeder implements ApplicationRunner {

    private final DisclosureSurfaceRepo surfaceRepo;
    private final LocalAggregateFactRepo factRepo;
    private final StudyRepo studyRepo;
    private final com.ctrial.disclosure.PublishGate gate;
    private final boolean seed;

    // Centre -> age -> enrolled per period (P1,P2,P3). Age C at CN-02 is the tiny group.
    private static final Map<String, Map<String, int[]>> ENROLMENT = new LinkedHashMap<>();
    private static final Map<String, Map<String, int[]>> SERIOUS = new LinkedHashMap<>();

    static {
        ENROLMENT.put("CN-01", Map.of("A", new int[]{6, 5, 5}, "B", new int[]{6, 5, 5}, "C", new int[]{6, 5, 5}));
        // CN-02 age C: 5 enrolled in total (2+2+1) so an age-level cell clears the floor, but each
        // period cell (2,2,1) is below it — the exact setup where period-level suppression can be
        // defeated by a public per-period total.
        ENROLMENT.put("CN-02", Map.of("A", new int[]{6, 5, 5}, "B", new int[]{5, 6, 5}, "C", new int[]{2, 2, 1}));
        ENROLMENT.put("SG-01", Map.of("A", new int[]{6, 5, 5}, "B", new int[]{6, 5, 5}, "C", new int[]{6, 5, 5}));

        // Serious cases reported by day 45 (the v1 watermark). P3 events are reported on day 50,
        // so day 45 sees none of them and day 60 folds them in — a genuine increment.
        SERIOUS.put("CN-01", Map.of("A", new int[]{0, 0, 0}, "B", new int[]{1, 0, 0}, "C", new int[]{0, 1, 0}));
        SERIOUS.put("CN-02", Map.of("A", new int[]{0, 0, 0}, "B", new int[]{0, 1, 0}, "C", new int[]{1, 1, 0}));
        SERIOUS.put("SG-01", Map.of("A", new int[]{0, 0, 0}, "B", new int[]{1, 0, 0}, "C", new int[]{0, 1, 0}));
    }

    /** Serious cases by day 60: P3 reports arrive for a few subjects. */
    private static final Map<String, Map<String, int[]>> SERIOUS_DAY60 = new LinkedHashMap<>();

    static {
        SERIOUS_DAY60.put("CN-01", Map.of("A", new int[]{0, 0, 1}, "B", new int[]{1, 0, 0}, "C", new int[]{0, 1, 0}));
        SERIOUS_DAY60.put("CN-02", Map.of("A", new int[]{0, 0, 0}, "B", new int[]{0, 1, 0}, "C", new int[]{1, 1, 1}));
        SERIOUS_DAY60.put("SG-01", Map.of("A", new int[]{0, 0, 1}, "B", new int[]{1, 0, 0}, "C", new int[]{0, 1, 0}));
    }

    public DisclosureSeeder(DisclosureSurfaceRepo surfaceRepo, LocalAggregateFactRepo factRepo,
                            StudyRepo studyRepo, com.ctrial.disclosure.PublishGate gate,
                            @Value("${app.demo.seed-on-startup:true}") boolean seed) {
        this.surfaceRepo = surfaceRepo;
        this.factRepo = factRepo;
        this.studyRepo = studyRepo;
        this.gate = gate;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seed || studyRepo.count() == 0 || factRepo.count() > 0) {
            return;
        }
        seedFacts();
        seedSurfaces();
        seedBaselineReleases();
    }

    /**
     * Two historical tables on SURF-SAFETY-V1 that are individually fine but
     * differ by exactly one centre under the same deterministic mechanism. The
     * analyser flags them SUBTRACTIVE_RISK on first load, and the gate then
     * refuses any new table that would extend that differencing chain.
     */
    private void seedBaselineReleases() {
        // 1) all three sites, per-site (FINE) centre grouping, ages pooled over periods
        gate.admitHistorical("REL-BASE-001",
                new com.ctrial.disclosure.QuerySpec("SURF-SAFETY-V1",
                        List.of("AGE_BAND", "CENTER_GROUP"), "FINE",
                        List.of("CN-01", "CN-02", "SG-01"), null, "SERIOUS_CASE_COUNT", "SUPPRESSION",
                        "OVERLAPPING_LEDGER", "Baseline: all sites, pooled periods"),
                100.0, "system:seed", "Baseline historical summary: all three sites, ages A/B/C.");
        // 2) the same table minus CN-02 -> the difference isolates CN-02
        gate.admitHistorical("REL-BASE-002",
                new com.ctrial.disclosure.QuerySpec("SURF-SAFETY-V1",
                        List.of("AGE_BAND", "CENTER_GROUP"), "FINE",
                        List.of("CN-01", "SG-01"), null, "SERIOUS_CASE_COUNT", "SUPPRESSION",
                        "OVERLAPPING_LEDGER", "Baseline: excluding CN-02"),
                100.0, "system:seed",
                "Baseline: same surface and mechanism as REL-BASE-001 but without CN-02 — a subtractive pair.");
        // 3) LEGACY period-level table admitted before the gate existed. Its CN-02 age-C period
        // cells are suppressed, but the public CN-02|P1 total recovers one of them exactly. The
        // simulator exposes the leaked interval and the tracker records a finding — the cautionary
        // baseline the gate now prevents us from repeating.
        gate.admitHistorical("REL-BASE-LEGACY",
                new com.ctrial.disclosure.QuerySpec("SURF-SAFETY-V1",
                        List.of("AGE_BAND", "CENTER_GROUP", "PERIOD"), "FINE",
                        null, null, "SERIOUS_CASE_COUNT", "SUPPRESSION",
                        "OVERLAPPING_LEDGER", "Legacy period-level summary (pre-gate)"),
                100.0, "system:seed",
                "LEGACY: period-level table admitted before the disclosure gate existed. "
                        + "CN-02 age-C cells are suppressed but recoverable via the public CN-02|P1 total.");
    }

    private void seedFacts() {
        int ref = 1;
        for (String centre : ENROLMENT.keySet()) {
            Map<String, int[]> enrol = ENROLMENT.get(centre);
            Map<String, int[]> serious45 = SERIOUS.get(centre);
            Map<String, int[]> serious60 = SERIOUS_DAY60.get(centre);
            for (String age : List.of("A", "B", "C")) {
                int[] e = enrol.get(age);
                int[] s45 = serious45.get(age);
                int[] s60 = serious60.get(age);
                for (int p = 0; p < 3; p++) {
                    String period = "P" + (p + 1);
                    int enrolCount = e[p];
                    int seriousAt45 = s45[p];
                    int seriousAt60 = s60[p];
                    for (int i = 0; i < enrolCount; i++) {
                        LocalAggregateFact f = new LocalAggregateFact();
                        f.setLocalRef(String.format("LAF-%04d", ref++));
                        f.setAgeBand(age);
                        f.setCenter(centre);
                        f.setPeriod(period);
                        // Everyone enrolled before the day-45 watermark unless they are a P3-only
                        // arrival; P3 enrollment is day 41..44 so it is present at day 60 too but
                        // not necessarily at day 45 — keep them before 45 so membership is identical.
                        f.setEnrolledDay(enrolDay(period, i, enrolCount));
                        // A subject with a serious event at day 45 reports it inside the period;
                        // one that only becomes serious by day 60 reports it after day 45.
                        if (i < seriousAt45) {
                            f.setSeriousReportedDay(seriousDay(period, i, 45));
                        } else if (i < seriousAt60) {
                            f.setSeriousReportedDay(seriousDay(period, i, 60));
                        }
                        factRepo.save(f);
                    }
                }
            }
        }
    }

    private int enrolDay(String period, int idx, int count) {
        // All enrollment lands before day 45 so the day-45 and day-60 cohorts are the same people.
        int base = switch (period) {
            case "P1" -> 3;
            case "P2" -> 21;
            default -> 41;
        };
        return base + Math.min(idx, 3);
    }

    private int seriousDay(String period, int idx, int watermark) {
        if (watermark == 60) {
            return 52 + idx; // reported after day 45, folded in by day 60
        }
        int base = switch (period) {
            case "P1" -> 10;
            case "P2" -> 30;
            default -> 44; // just before the day-45 watermark
        };
        return base + idx;
    }

    private void seedSurfaces() {
        // v1: analysis day 45. Everything reported through P1..P2 fully, P3 partly.
        createSurface("SURF-SAFETY-V1", "SAFETY-COHORT-2026", 1, 45, DisclosureSurface.AgeRecode.BASE,
                "ALL", "round to nearest 5", 5,
                "CN-01=48;CN-02=37;SG-01=48",
                // Registry serious totals: the CN-02 period-1 total (=1) is the public join that
                // recovers a suppressed CN-02 age-C/P1 cell from a period-level table.
                "CN-02=3;CN-02|P1=1",
                null, null,
                "Frozen safety summary at day 45. Three age bands, per-site centre groups. "
                        + "CN-02 age C holds only 5 members; at period granularity each cell is below the floor.");

        // v2: analysis day 60 — later watermark, same lineage, adds serious events to existing members.
        createSurface("SURF-SAFETY-V2", "SAFETY-COHORT-2026", 2, 60, DisclosureSurface.AgeRecode.BASE,
                "ALL", "round to nearest 5", 5,
                "CN-01=48;CN-02=37;SG-01=48",
                "CN-02=4;CN-02|P1=1",
                "SURF-SAFETY-V1",
                null,
                "Day-60 version of the same face. The membership is the day-45 cohort; P3 events reported "
                        + "after day 45 are newly folded in, so this is an increment of the same people.");

        // v3: recoded under a sponsor purpose change — ages collapse to 18-64 / 65+.
        createSurface("SURF-SAFETY-V3", "SAFETY-COHORT-2026", 3, 60, DisclosureSurface.AgeRecode.AGE_COARSE_V2,
                "ALL", "round to nearest 5", 5,
                "CN-01=48;CN-02=37;SG-01=48",
                null,
                "SURF-SAFETY-V2",
                "SURF-SAFETY-V2",
                "Purpose change forced a coarser age recoding. The ledger opens a comparability edge to v2 "
                        + "rather than reusing its budget: the bands are not one-to-one with the old ones.");
    }

    private void createSurface(String code, String lineage, int version, int day, DisclosureSurface.AgeRecode recode,
                               String cohort, String rounding, int metric, String publicEnrolment,
                               String publicTotals, String comparableOf, String comparabilityNote, String note) {
        DisclosureSurface s = new DisclosureSurface();
        s.setCode(code);
        s.setLineage(lineage);
        s.setVersion(version);
        s.setPurpose(Purpose.SAFETY_MONITORING);
        s.setProtocolVersions("V1.2,V2.0");
        s.setFieldGroups("DEMOG,AE");
        s.setObservationFromDay(1);
        s.setObservationToDay(day);
        s.setAnalysisDay(day);
        s.setAgeRecode(recode);
        s.setStatus(SurfaceState.ACTIVE);
        s.setCohortFilter("centerGroup=ALL;ageRecode=" + recode + ";cohort=" + cohort);
        s.setRoundingPolicy(rounding);
        s.setRoundingUnit(5);
        s.setMinGroupSize(metric);
        s.setPublicEnrollment(publicEnrolment);
        s.setPublicTotals(publicTotals);
        s.setFrozenAt(Instant.now());
        s.setComparableOf(comparableOf);
        s.setComparabilityNote(comparabilityNote);
        s.setNote(note);
        String definition = "lineage=" + lineage + ";purpose=" + s.getPurpose() + ";protocols="
                + s.getProtocolVersions() + ";fields=" + s.getFieldGroups() + ";obsDays=" + s.getObservationFromDay()
                + ".." + s.getObservationToDay() + ";analysisDay=" + day + ";ageRecode=" + recode
                + ";cohort=" + cohort + ";minGroup=" + metric;
        s.setDefinition(definition);
        s.setDefinitionDigest(com.ctrial.common.Text.sha256(definition));
        // A surface that re-codes a prior one is marked RECODED; a same-recode later version
        // supersedes its predecessor. Both keep a comparability edge for the ledger.
        surfaceRepo.save(s);
        if (version > 1 && comparableOf != null) {
            surfaceRepo.findByCode(comparableOf).ifPresent(old -> {
                DisclosureSurface.AgeRecode oldRecode = old.getAgeRecode();
                if (oldRecode == recode) {
                    old.setStatus(SurfaceState.SUPERSEDED);
                    surfaceRepo.save(old);
                }
            });
        }
        if (s.getComparableOf() != null && !s.getComparableOf().isBlank()) {
            DisclosureSurface prior = surfaceRepo.findByCode(s.getComparableOf()).orElse(null);
            if (prior != null && prior.getAgeRecode() != recode) {
                s.setStatus(SurfaceState.RECODED);
                surfaceRepo.save(s);
            }
        }
    }
}
