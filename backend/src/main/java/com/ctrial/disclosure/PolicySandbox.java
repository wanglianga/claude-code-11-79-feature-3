package com.ctrial.disclosure;

import com.ctrial.model.DisclosureSurface;
import com.ctrial.model.Enums.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * The <b>policy sandbox</b>. Given a base request and a chosen policy it shows
 * what the requester may and may not learn: which cells stay answerable, which
 * are suppressed, and — crucially — what inference intervals the differencing
 * attacker can still derive from the resulting table set.
 *
 * <p>Policies mirror the four levers in the brief: defer publication, merge cells
 * (coarsen), switch to consistent randomised perturbation, or refuse a
 * high-overlap request outright.
 */
@Service
public class PolicySandbox {

    public enum Policy {
        PUBLISH_AS_IS,
        DEFER,
        MERGE_CELLS,
        CONSISTENT_PERTURBATION,
        REFUSE_HIGH_OVERLAP
    }

    private final PublishGate gate;
    private final ReleaseLedger ledger;

    public PolicySandbox(PublishGate gate, ReleaseLedger ledger) {
        this.gate = gate;
        this.ledger = ledger;
    }

    public record PolicyOutcome(String policy, String label, boolean answerable, String decision,
                                String wouldReleaseAs, int publishedCells, int suppressedCells,
                                List<PublishGate.PreviewCell> cells, List<String> inferredIntervals,
                                List<String> answerableQuestions, List<String> blockedQuestions,
                                double projectedLoss, double resultingCumulativeLoss, String rationale) {
    }

    public record SandboxReport(String request, DisclosureSurface surface, double budgetCap,
                                double currentCumulativeLoss, List<PolicyOutcome> outcomes) {
    }

    public SandboxReport evaluate(QuerySpec base, DisclosureSurface surface, double budgetCap) {
        List<PolicyOutcome> outcomes = new ArrayList<>();
        outcomes.add(outcome(Policy.PUBLISH_AS_IS, "Publish as requested", base, surface, budgetCap));
        outcomes.add(outcome(Policy.DEFER, "Defer until the next watermark", base, surface, budgetCap));
        outcomes.add(outcome(Policy.MERGE_CELLS, "Merge cells (coarsen grouping)",
                merge(base), surface, budgetCap));
        outcomes.add(outcome(Policy.CONSISTENT_PERTURBATION, "Consistent randomised perturbation",
                withMechanism(base, "CONSISTENT_RANDOM"), surface, budgetCap));
        outcomes.add(refuse(base, surface, budgetCap));
        double current = Math.round(ledger.snapshotsOfLineage(surface.getLineage()).stream()
                .mapToDouble(s -> s.release().getIncrementalLoss()).sum() * 1000.0) / 1000.0;
        return new SandboxReport(describe(base), surface, budgetCap, current, outcomes);
    }

    private PolicyOutcome outcome(Policy policy, String label, QuerySpec spec, DisclosureSurface surface,
                                  double budgetCap) {
        PublishGate.Preview preview = gate.preview(spec, surface, budgetCap);
        boolean answerable = !preview.blocked();
        String decision = switch (policy) {
            case DEFER -> preview.blocked()
                    ? "DEFER — withheld now; the blocking overlap is re-evaluated when new data lands"
                    : "DEFER — eligible now, but held so the watermark can accumulate and avoid a subtractive pair";
            case MERGE_CELLS -> preview.blocked()
                    ? "MERGE — coarser regrouping reduces the equation count; still blocked, merge further"
                    : "MERGE — cells folded into a coarser regrouping";
            case CONSISTENT_PERTURBATION -> preview.blocked()
                    ? "PERTURB — randomised mechanism alone does not clear a high-overlap difference; still blocked"
                    : "PERTURB — consistent noise widens inference intervals without breaking cross-version comparability";
            default -> preview.blocked() ? "BLOCK — " + preview.findings() : "PUBLISH — " + preview.verdict();
        };
        List<String> answerableQs = new ArrayList<>();
        List<String> blockedQs = new ArrayList<>();
        for (PublishGate.PreviewCell c : preview.cells()) {
            if (c.suppressed()) {
                blockedQs.add(c.label() + " (suppressed: " + c.suppressionReason() + ")");
            } else {
                answerableQs.add(c.label() + " = " + c.publishedValue());
            }
        }
        List<String> intervals = preview.inferredIntervals().stream()
                .map(i -> i.cellKey() + " ∈ [" + (int) i.lo() + "," + (int) i.hi() + "] w=" + (int) i.width()
                        + (i.leaked() ? " ⚠" : ""))
                .toList();
        double resulting = preview.cumulativeLoss() + (preview.blocked() ? 0 : preview.projectedLoss());
        return new PolicyOutcome(policy.name(), label, answerable, decision, preview.wouldPublishAs(),
                preview.publishedCells(), preview.suppressedCells(), preview.cells(), intervals,
                answerableQs, blockedQs, preview.projectedLoss(), Math.round(resulting * 1000.0) / 1000.0,
                preview.rationale());
    }

    private PolicyOutcome refuse(QuerySpec base, DisclosureSurface surface, double budgetCap) {
        return new PolicyOutcome(Policy.REFUSE_HIGH_OVERLAP.name(), "Refuse high-overlap request", false,
                "REFUSE — nothing is published; the requester must reformulate with a coarser or disjoint face",
                "WITHHELD", 0, 0, List.of(), List.of(), List.of(), List.of(), 0.0,
                Math.round(ledger.snapshotsOfLineage(surface.getLineage()).stream()
                        .mapToDouble(s -> s.release().getIncrementalLoss()).sum() * 1000.0) / 1000.0,
                "High-overlap refusal keeps the ledger closed: the request is logged but contributes zero new "
                        + "knowledge, so it cannot be differenced against anything.");
    }

    /** Coarsen: force the coarse centre grouping and drop the finest dimension when possible. */
    private QuerySpec merge(QuerySpec base) {
        List<String> dims = new ArrayList<>(base.dimensions());
        // drop one dimension (the finest) only if more than one is present; never leave it empty
        if (dims.size() > 1) {
            dims.remove(dims.size() - 1);
        }
        return new QuerySpec(base.surfaceCode(), dims, "COARSE", null, base.periods(), base.metric(),
                base.mechanism(), base.route(), base.label() + " (merged: " + String.join(",", dims) + ")");
    }

    private QuerySpec withMechanism(QuerySpec base, String mechanism) {
        return new QuerySpec(base.surfaceCode(), base.dimensions(), base.centerGranularity(), base.centers(),
                base.periods(), base.metric(), mechanism, base.route(), base.label() + " (perturbed)");
    }

    private String describe(QuerySpec base) {
        return base.label() + " — dims " + String.join(",", base.dimensions())
                + ", " + base.centerGranularity() + " centres, periods " + base.periodsCsv();
    }

    public RiskLevel riskOf(PolicyOutcome o) {
        if (!o.answerable()) {
            return RiskLevel.BLOCKED;
        }
        if (!o.inferredIntervals().isEmpty()
                && o.inferredIntervals().stream().anyMatch(s -> s.contains("⚠"))) {
            return RiskLevel.HIGH;
        }
        return RiskLevel.LOW;
    }
}
