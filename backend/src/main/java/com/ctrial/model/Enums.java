package com.ctrial.model;

public final class Enums {

    private Enums() {
    }

    /**
     * Approved processing purpose. ALL is only valid inside a consent grant,
     * never as the purpose of a query or a delivery.
     */
    public enum Purpose {
        GLOBAL_ANALYSIS,
        CHINA_LOCAL_ANALYSIS,
        LOCAL_ANALYSIS,
        SAFETY_MONITORING,
        REGULATORY_SUBMISSION,
        ALL;

        public boolean isGlobal() {
            return this == GLOBAL_ANALYSIS;
        }

        public boolean isLocal() {
            return this == CHINA_LOCAL_ANALYSIS || this == LOCAL_ANALYSIS;
        }
    }

    /** Destination / source region codes. GLOBAL = any approved region. */
    public enum Region {
        GLOBAL, EU, CN, SG, DE, TH;

        public boolean covers(String region) {
            return this == GLOBAL || name().equalsIgnoreCase(region);
        }
    }

    /** Data domains flowing through the source ledger. Each has its own watermark. */
    public enum Domain {
        VISIT, LAB, AE, IMAGING, RANDOMIZATION, DEVIATION
    }

    public enum ConsentKind {
        GRANT, WITHDRAWAL
    }

    public enum ProtocolStatus {
        DRAFT,
        PARTIALLY_APPROVED,
        APPROVED,
        WITHDRAWN
    }

    /** Per-record availability. The three outcomes required by the spec. */
    public enum Eligibility {
        LOCAL_AVAILABLE,
        GLOBAL_AVAILABLE,
        PENDING,
        /** No lawful basis in any plane; reported separately, never silently counted as zero. */
        BLOCKED
    }

    /** Finer-grained adjudication used alongside the three availability outcomes. */
    public enum Verdict {
        ELIGIBLE,
        UNDETERMINED,
        NOT_ELIGIBLE
    }

    public enum SnapshotStatus {
        VALID,
        STALE,
        AUDIT_ONLY
    }

    public enum DeliveryStatus {
        ACTIVE,
        ACCESS_REVOKED,
        DESTROYED
    }

    public enum PlaneType {
        GLOBAL,
        LOCAL
    }

    public enum RuleAction {
        RETAIN_RESTRICTED,
        DESTROY_AFTER
    }

    public enum DispositionScope {
        STUDY,
        SITE
    }

    public enum DispositionState {
        APPLIED,
        SCHEDULED,
        EXECUTED
    }

    public enum QuarantineKind {
        RACE_WITHDRAWAL_CORRECTION,
        DUPLICATE_HASH_CONFLICT,
        CROSS_DOMAIN_MISMATCH,
        WATERMARK_INCONSISTENCY
    }

    public enum QuarantineState {
        OPEN,
        RESOLVED
    }

    public enum RecoveryStatus {
        PASS,
        FINDINGS
    }

    public enum Role {
        ADMIN,
        GLOBAL_ANALYST,
        CN_ANALYST,
        AUDITOR,
        SPONSOR
    }

    // ------------------------------------------------------------------
    // Aggregate disclosure control vocabulary
    // ------------------------------------------------------------------

    /**
     * Protection applied to an aggregate cell. {@code NONE} is only ever used
     * for a cell that is already large enough; a cell below the minimum group
     * size is always suppressed regardless of mechanism.
     */
    public enum DisclosureMechanism {
        NONE,
        SUPPRESSION,
        ROUNDING,
        LAPLACE,
        CONSISTENT_RANDOM
    }

    /** Release lifecycle. BLOCKED and DEFERRED releases are recorded, not silently dropped. */
    public enum ReleaseStatus {
        RELEASED,
        BLOCKED,
        DEFERRED,
        MERGED,
        SANDBOX
    }

    /** How a request relates to what is already in the ledger. */
    public enum BudgetKind {
        NEW_QUERY,
        NEW_DATA_INCREMENT,
        DUPLICATE_QUERY,
        DEFINITION_CHANGE
    }

    /** Edge vocabulary of the release relation graph. */
    public enum RelationType {
        SAME_SURFACE,
        NESTED_SUBSET,
        SUPERSET_VERSION,
        RECODED_DIMENSION,
        DUPLICATE,
        SUBTRACTIVE_RISK,
        OVERLAPPING,
        DISJOINT
    }

    public enum RiskLevel {
        NONE,
        LOW,
        MEDIUM,
        HIGH,
        BLOCKED
    }

    /** Cell dimensions understood by the aggregate model. */
    public enum DimensionKind {
        AGE_BAND,
        CENTER_GROUP,
        PERIOD
    }

    public enum SurfaceState {
        ACTIVE,
        SUPERSEDED,
        RECODED
    }

    /** Chosen disclosure route for a study lineage. */
    public enum DisclosureRoute {
        /** Predefined, coarse query lattice frozen and released once; coarse but auditable. */
        RESTRICTED_LATTICE,
        /** Overlapping-query ledger + privacy budget + cross-version correlated perturbation. */
        OVERLAPPING_LEDGER
    }
}
