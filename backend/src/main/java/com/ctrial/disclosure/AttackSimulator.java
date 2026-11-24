package com.ctrial.disclosure;

import com.ctrial.model.AttackSimulation;
import com.ctrial.model.DisclosureSurface;
import com.ctrial.model.Enums.RiskLevel;
import com.ctrial.repo.AttackSimulationRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * The <b>differencing attack simulator</b>. Given the already-published tables
 * (plus public background), it enumerates the tightest derivable interval for
 * each <i>suppressed small group</i> using only public information, and reports
 * when the enumeration pins that group or narrows it to a width of 1 or less.
 *
 * <p>Contract enforced in code:
 * <ul>
 *   <li>the equation system is built by {@link AttackerModel} from published
 *       tables and public totals only — local true counts never enter it;</li>
 *   <li>only suppressed groups are targets: a cell that a table lawfully
 *       published above the floor is not a leak.</li>
 * </ul>
 */
@Service
public class AttackSimulator {

    private final AttackerModel attacker;
    private final AttackSimulationRepo simRepo;

    public AttackSimulator(AttackerModel attacker, AttackSimulationRepo simRepo) {
        this.attacker = attacker;
        this.simRepo = simRepo;
    }

    public record TargetInterval(String cellKey, int analysisDay, double lo, double hi, double width,
                                 RiskLevel risk, boolean leaked, int denominator) {
    }

    public record SimResult(String code, String scope, String knowledge, int variables, int equations,
                            int targets, List<TargetInterval> intervals, int belowBound, int exactCells,
                            boolean blocked, String verdict, List<String> notes) {
    }

    /** Simulate without persisting (the publish gate's pre-check). */
    public SimResult dryRun(List<ReleaseSnapshot> visible, DisclosureSurface publicSurface) {
        return run(visible, publicSurface, "gate", false, null);
    }

    /**
     * Pre-check scoped to the suppressed groups <b>owned by one release</b> (the
     * candidate). This is the precise publish rule: a request is refused when its
     * <i>own</i> suppressed cells become recoverable, regardless of unrelated
     * legacy leaks elsewhere in the ledger.
     */
    public SimResult dryRunForOwner(List<ReleaseSnapshot> visible, DisclosureSurface publicSurface, String ownerCode) {
        return run(visible, publicSurface, "gate", false, ownerCode);
    }

    /** Persist a named simulation for the UI. */
    @Transactional
    public SimResult runAndSave(List<ReleaseSnapshot> visible, DisclosureSurface publicSurface, String actor) {
        return run(visible, publicSurface, actor, true, null);
    }

    private SimResult run(List<ReleaseSnapshot> visible, DisclosureSurface publicSurface, String actor,
                          boolean persist, String ownerCode) {
        AttackerModel.Model model = attacker.build(visible, publicSurface);
        List<AttackerModel.TargetGroup> groups = attacker.suppressedGroups(visible).stream()
                .filter(g -> ownerCode == null || ownerCode.equals(g.releaseCode()))
                .toList();
        List<TargetInterval> targets = new ArrayList<>();
        int below = 0;
        int exact = 0;
        for (AttackerModel.TargetGroup g : groups) {
            Optional<AttackerModel.GroupInterval> gi = attacker.intervalOf(model, g);
            if (gi.isEmpty()) {
                continue;
            }
            AttackerModel.GroupInterval iv = gi.get();
            boolean leaked = iv.exact() || iv.nearExact();
            RiskLevel risk = iv.exact() ? RiskLevel.BLOCKED : iv.nearExact() ? RiskLevel.HIGH : RiskLevel.LOW;
            if (iv.exact()) {
                exact++;
            }
            if (leaked) {
                below++;
            }
            targets.add(new TargetInterval(g.cellKey(), g.analysisDay(), iv.lo(), iv.hi(), iv.width(), risk,
                    leaked, g.denominator()));
        }
        boolean blocked = targets.stream().anyMatch(t -> t.risk() == RiskLevel.BLOCKED);
        boolean anyLeaked = targets.stream().anyMatch(TargetInterval::leaked);
        String verdict;
        if (blocked) {
            verdict = "BLOCKED — a suppressed small group is pinned to a single count by public information alone";
        } else if (anyLeaked) {
            verdict = "REFUSE — a suppressed small group is derivable to width ≤ 1; merge or withhold";
        } else {
            verdict = "SAFE — every suppressed small group keeps an inference interval wider than 1";
        }

        boolean hasPublicTotals = publicSurface != null && publicSurface.getPublicTotals() != null
                && !publicSurface.getPublicTotals().isBlank();
        List<String> notes = new ArrayList<>();
        notes.add("Attacker knowledge: published tables + public enrolment scale"
                + (hasPublicTotals ? " + public totals" : "")
                + ". Local unpublished detail is NOT used.");
        notes.add("Targets are the suppressed (small-group) cells; inference is judged against width ≤ 1.");
        notes.add("Intervals are version-qualified; calendar versions of a cell are linked by monotonicity, so a "
                + "new watermark is an increment, never an independent population.");
        notes.add("Mechanism tolerances: rounding/suppression treated as exact (0), Laplace = 1, consistent-random = 0.5.");
        if (anyLeaked) {
            notes.add(targets.stream().filter(TargetInterval::leaked).count()
                    + " suppressed group(s) become derivable beyond lawful publication.");
        }

        String knowledge = "PUBLISHED_ONLY + PUBLIC_ENROLLMENT" + (hasPublicTotals ? " + PUBLIC_TOTALS" : "");
        String scope = "SUPPRESSED_GROUPS(" + groups.size() + ")";
        if (persist) {
            String code = "SIM-" + String.format("%04d", simRepo.count() + 1);
            AttackSimulation sim = new AttackSimulation();
            sim.setCode(code);
            sim.setScope(scope);
            sim.setKnowledge(knowledge);
            sim.setVariables(model.variableCount());
            sim.setEquations(model.equations().size());
            sim.setTargetsAnalyzed(targets.size());
            sim.setBelowBound(below);
            sim.setExactCells(exact);
            sim.setBlocked(blocked);
            sim.setVerdict(verdict);
            sim.setIntervalsJson(toJson(targets));
            sim.setCreatedAt(Instant.now());
            sim.setRequestedBy(actor);
            sim.setNote("differencing simulation over the published ledger");
            simRepo.save(sim);
            return new SimResult(code, scope, knowledge, model.variableCount(), model.equations().size(),
                    targets.size(), targets, below, exact, blocked, verdict, notes);
        }
        return new SimResult("SIM-PREVIEW", scope, knowledge, model.variableCount(), model.equations().size(),
                targets.size(), targets, below, exact, blocked, verdict, notes);
    }

    public List<AttackSimulation> history() {
        return simRepo.findAllByOrderByCreatedAtDesc();
    }

    private String toJson(List<TargetInterval> targets) {
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (TargetInterval t : targets) {
            if (!first) {
                sb.append(",");
            }
            first = false;
            sb.append("{\"cellKey\":\"").append(escape(t.cellKey())).append("\",\"day\":").append(t.analysisDay())
                    .append(",\"lower\":").append(t.lo()).append(",\"upper\":").append(t.hi())
                    .append(",\"width\":").append(t.width()).append(",\"denominator\":").append(t.denominator())
                    .append(",\"risk\":\"").append(t.risk()).append("\",\"leaked\":").append(t.leaked()).append("}");
        }
        return sb.append("]").toString();
    }

    private String escape(String s) {
        return s == null ? "" : s.replace("\"", "'");
    }
}
