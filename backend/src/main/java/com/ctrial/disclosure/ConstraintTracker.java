package com.ctrial.disclosure;

import com.ctrial.model.*;
import com.ctrial.model.Enums.RiskLevel;
import com.ctrial.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Tracks <b>constraint groups</b>: connected components of published releases
 * that share atomic cells. Within a group every published cell is a linear
 * equation over the same versioned atoms, so the group as a whole can pin a
 * suppressed small group that no single release would. The tracker rebuilds the
 * group after each publish, enumerates each suppressed group's interval, and
 * records the ones pinned or narrowed to width ≤ 1 — the "constraint group
 * narrowed under the risk floor" finding the brief requires.
 */
@Service
public class ConstraintTracker {

    private final ConstraintFindingRepo findingRepo;
    private final AttackerModel attacker;

    public ConstraintTracker(ConstraintFindingRepo findingRepo, AttackerModel attacker) {
        this.findingRepo = findingRepo;
        this.attacker = attacker;
    }

    public record CellFinding(String cellKey, int analysisDay, int lo, int hi, int width, int denominator,
                              RiskLevel risk, boolean violated, String sources, String basis) {
    }

    public record GroupReport(String groupKey, int releases, int atoms, int variables, int equations,
                              List<CellFinding> cells, int violations) {
    }

    public String groupKey(List<ReleaseSnapshot> group) {
        TreeSet<String> parts = new TreeSet<>();
        for (ReleaseSnapshot s : group) {
            parts.add(s.release().getSurface().getDefinitionDigest());
        }
        return "CG-" + Integer.toHexString(String.join("+", parts).hashCode()).replace("-", "")
                + "-" + group.size();
    }

    @Transactional
    public GroupReport analyze(List<ReleaseSnapshot> group, String groupKey) {
        findingRepo.deleteByGroupKey(groupKey);
        if (group.isEmpty()) {
            return new GroupReport(groupKey, 0, 0, 0, 0, List.of(), 0);
        }
        DisclosureSurface surface = group.get(0).release().getSurface();
        AttackerModel.Model model = attacker.build(group, surface);
        List<AttackerModel.TargetGroup> groups = attacker.suppressedGroups(group);

        List<CellFinding> findings = new ArrayList<>();
        int violations = 0;
        for (AttackerModel.TargetGroup g : groups) {
            Optional<AttackerModel.GroupInterval> gi = attacker.intervalOf(model, g);
            if (gi.isEmpty()) {
                continue;
            }
            AttackerModel.GroupInterval iv = gi.get();
            boolean violated = iv.exact() || iv.nearExact();
            RiskLevel risk = iv.exact() ? RiskLevel.BLOCKED : iv.nearExact() ? RiskLevel.HIGH : RiskLevel.LOW;
            if (violated) {
                violations++;
            }
            String basis = iv.exact()
                    ? "pinned to a single count by the combined equations"
                    : iv.nearExact()
                    ? "combined equations bound the group to width " + (int) iv.width()
                    : "interval width " + (int) iv.width() + " — still protected";
            CellFinding cf = new CellFinding(g.cellKey(), g.analysisDay(), (int) iv.lo(), (int) iv.hi(),
                    (int) iv.width(), g.denominator(), risk, violated,
                    "group in " + g.releaseCode(), basis);
            findings.add(cf);
            if (violated) {
                persist(groupKey, cf, group.size());
            }
        }
        return new GroupReport(groupKey, group.size(), model.atomCount(), model.variableCount(),
                model.equations().size(), findings, violations);
    }

    private void persist(String groupKey, CellFinding cf, int groupSize) {
        ConstraintFinding f = new ConstraintFinding();
        f.setGroupKey(groupKey);
        f.setCellKey(cf.cellKey());
        f.setDims(cf.cellKey());
        f.setLowerBound(cf.lo());
        f.setUpperBound(cf.hi());
        f.setWidth(cf.width());
        f.setRiskLowerBound(cf.denominator());
        f.setRisk(cf.risk());
        f.setViolated(true);
        f.setSourceReleases(truncate(cf.sources(), 590));
        f.setBasis(cf.basis());
        f.setNote("group of " + groupSize + " release(s)");
        f.setCreatedAt(Instant.now());
        findingRepo.save(f);
    }

    public List<ConstraintFinding> findings() {
        return findingRepo.findAllByOrderByIdAsc();
    }

    public List<ConstraintFinding> violations() {
        return findingRepo.findByViolatedTrueOrderByIdAsc();
    }

    private String truncate(String s, int n) {
        return s == null ? "" : (s.length() <= n ? s : s.substring(0, n));
    }
}
