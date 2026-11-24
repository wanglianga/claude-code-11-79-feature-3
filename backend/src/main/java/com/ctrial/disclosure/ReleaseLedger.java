package com.ctrial.disclosure;

import com.ctrial.model.*;
import com.ctrial.model.Enums.*;
import com.ctrial.repo.*;
import com.ctrial.service.AuditService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * The <b>aggregate release ledger</b>. Records every aggregate request — the
 * ones that were published and the ones that were blocked or deferred — with a
 * frozen analysis surface, grouping dimensions, canonical filter, denominator
 * scope, suppression outcome, mechanism, result version and the budget it
 * consumed. It stores <b>no subject-level members</b>: only aggregates plus a
 * one-way membership digest.
 */
@Service
public class ReleaseLedger {

    private final DisclosureReleaseRepo releaseRepo;
    private final DisclosureCellRepo cellRepo;
    private final DisclosureSurfaceRepo surfaceRepo;
    private final ReleaseRelationRepo relationRepo;
    private final AuditService audit;

    public ReleaseLedger(DisclosureReleaseRepo releaseRepo, DisclosureCellRepo cellRepo,
                         DisclosureSurfaceRepo surfaceRepo, ReleaseRelationRepo relationRepo, AuditService audit) {
        this.releaseRepo = releaseRepo;
        this.cellRepo = cellRepo;
        this.surfaceRepo = surfaceRepo;
        this.relationRepo = relationRepo;
        this.audit = audit;
    }

    public Optional<DisclosureSurface> surface(String code) {
        return surfaceRepo.findByCode(code);
    }

    public List<DisclosureSurface> surfaces() {
        return surfaceRepo.findAllByOrderByLineageAscVersionAsc();
    }

    public Optional<DisclosureRelease> find(String code) {
        return releaseRepo.findByCode(code);
    }

    public List<DisclosureRelease> all() {
        return releaseRepo.findAllByOrderByRequestedAtAscIdAsc();
    }

    public List<DisclosureRelease> published() {
        return releaseRepo.findAllByOrderByRequestedAtAscIdAsc().stream()
                .filter(DisclosureRelease::isPublished)
                .toList();
    }

    public List<ReleaseSnapshot> publishedSnapshots() {
        List<ReleaseSnapshot> out = new ArrayList<>();
        for (DisclosureRelease r : published()) {
            out.add(ReleaseSnapshot.of(r, cellsOf(r)));
        }
        return out;
    }

    public ReleaseSnapshot snapshot(DisclosureRelease r) {
        return ReleaseSnapshot.of(r, cellsOf(r));
    }

    public List<ReleaseSnapshot> snapshotsOfLineage(String lineage) {
        List<ReleaseSnapshot> out = new ArrayList<>();
        for (DisclosureRelease r : releaseRepo.findByLineageOrderByRequestedAtAscIdAsc(lineage)) {
            if (r.isPublished()) {
                out.add(ReleaseSnapshot.of(r, cellsOf(r)));
            }
        }
        return out;
    }

    public List<DisclosureCell> cellsOf(DisclosureRelease r) {
        return cellRepo.findByReleaseIdOrderByDimKeyAsc(r.getId());
    }

    public String nextCode(String lineage) {
        long n = releaseRepo.countByLineage(lineage);
        return "REL-" + shortLineage(lineage) + "-" + String.format("%03d", n + 1);
    }

    private String shortLineage(String lineage) {
        String s = lineage.replace("SURF-", "").replace(" ", "");
        return s.length() > 18 ? s.substring(0, 18) : s;
    }

    /**
     * Persist a release (in whatever status) together with its cells. Cells are
     * cloned from the built table; only aggregates and atom metadata are stored.
     */
    @Transactional
    public DisclosureRelease persist(QuerySpec spec, DisclosureSurface surface, AggregationEngine.BuiltTable table,
                                     DisclosureRelease template) {
        DisclosureRelease r = template;
        r.setSurface(surface);
        r.setLineage(surface.getLineage());
        r.setPurpose(surface.getPurpose());
        r.setDimensions(String.join(",", spec.dimensions()));
        r.setCenterGranularity(spec.centerGranularity());
        r.setFilterCanonical(spec.filterCanonical());
        r.setDenominatorScope("enrolledBy=" + surface.getAnalysisDay() + "; centers=" + spec.centersCsv()
                + "; periods=" + spec.periodsCsv());
        r.setMetric(spec.metric());
        r.setMetricDefinition("count of subjects with a serious AE reported on or before the analysis watermark");
        r.setMinGroupSize(surface.getMinGroupSize());
        r.setMechanism(mechanismOf(spec.mechanism()));
        r.setMechanismDetail(mechanismDetail(spec, surface));
        r.setRoundingUnit(surface.getRoundingUnit());
        r.setMembershipDigest(table.membershipDigest());
        r.setMemberCount(table.memberCount());
        r.setPublishedCells((int) table.publishedCount());
        r.setSuppressedCells((int) table.suppressedCount());
        r.setRequestedAt(Instant.now());
        if (r.getResultVersion() == null) {
            r.setResultVersion("v" + surface.getVersion() + "." + (releaseRepo.countByLineage(surface.getLineage()) + 1));
        }
        r = releaseRepo.save(r);
        List<DisclosureCell> cells = new ArrayList<>();
        for (AggregationEngine.BuiltCell bc : table.cells()) {
            DisclosureCell c = new DisclosureCell();
            c.setRelease(r);
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
        cellRepo.saveAll(cells);
        return r;
    }

    private DisclosureMechanism mechanismOf(String spec) {
        return switch (spec.toUpperCase()) {
            case "ROUNDING" -> DisclosureMechanism.ROUNDING;
            case "LAPLACE" -> DisclosureMechanism.LAPLACE;
            case "CONSISTENT_RANDOM" -> DisclosureMechanism.CONSISTENT_RANDOM;
            case "NONE" -> DisclosureMechanism.NONE;
            default -> DisclosureMechanism.SUPPRESSION;
        };
    }

    private String mechanismDetail(QuerySpec spec, DisclosureSurface surface) {
        return switch (spec.mechanism()) {
            case "ROUNDING" -> "round to nearest " + surface.getRoundingUnit()
                    + " (deterministic; identical across versions — treated as exact by the analyser)";
            case "LAPLACE" -> "Laplace noise scale 1.0, seeded per (lineage, day)";
            case "CONSISTENT_RANDOM" -> "correlated Laplace noise scale 0.5, seeded per (lineage, cell) so versions agree";
            default -> "suppression below minimum group; surviving cells published exactly";
        };
    }

    @Transactional
    public void recordBlocked(DisclosureRelease r, String reason) {
        r.setStatus(ReleaseStatus.BLOCKED);
        r.setBlockedReason(reason);
        releaseRepo.save(r);
        audit.record(r.getCreatedBy(), "DISCLOSURE_RELEASE_BLOCKED", "DisclosureRelease", r.getCode(), reason);
    }

    @Transactional
    public void markReleased(DisclosureRelease r) {
        r.setStatus(ReleaseStatus.RELEASED);
        releaseRepo.save(r);
        audit.record(r.getCreatedBy(), "DISCLOSURE_RELEASED", "DisclosureRelease", r.getCode(),
                r.getDimensions() + " on " + r.getSurface().getCode() + "; " + r.getPublishedCells()
                        + " cell(s) published, " + r.getSuppressedCells() + " suppressed; loss "
                        + String.format("%.2f", r.getIncrementalLoss()) + " (cumulative "
                        + String.format("%.2f", r.getCumulativeLoss()) + "/" + r.getBudgetCap() + ")");
    }

    public List<ReleaseRelation> relations() {
        return relationRepo.findAllByOrderByIdAsc();
    }

    @Transactional
    public void replaceRelationsOf(String fromCode) {
        relationRepo.findByFromCodeOrToCode(fromCode, fromCode).stream()
                .filter(r -> r.getFromCode().equals(fromCode))
                .forEach(relationRepo::delete);
    }
}
