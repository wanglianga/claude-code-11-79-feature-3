package com.ctrial.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A recorded <b>differencing-attack simulation</b>. The simulator is allowed to
 * know only: the released tables (and their stated mechanisms), the public
 * enrollment scale and any explicitly public background total. It is
 * constructed from the release ledger, never from local unpublished cell
 * detail. Its output is a set of derivable count intervals per target cell.
 */
@Entity
@Table(name = "attack_simulation", uniqueConstraints = {
        @UniqueConstraint(name = "uq_attack_code", columnNames = {"code"})
})
@Getter
@Setter
@NoArgsConstructor
public class AttackSimulation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false, length = 200)
    private String scope;

    /** The attacker knowledge model; always "PUBLISHED_ONLY+PUBLIC_ENROLLMENT(+PUBLIC_TOTAL)". */
    @Column(nullable = false, length = 300)
    private String knowledge;

    @Column(name = "variables", nullable = false)
    private int variables;

    @Column(name = "equations", nullable = false)
    private int equations;

    @Column(name = "targets_analyzed", nullable = false)
    private int targetsAnalyzed;

    @Column(name = "below_bound", nullable = false)
    private int belowBound;

    @Column(name = "exact_cells", nullable = false)
    private int exactCells;

    @Column(nullable = false)
    private boolean blocked;

    @Column(nullable = false, length = 300)
    private String verdict;

    /** JSON array of {cellKey, lower, upper, width, risk, leaked}. */
    @Column(name = "intervals_json", columnDefinition = "text")
    private String intervalsJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "requested_by", nullable = false)
    private String requestedBy;

    @Column(length = 1000)
    private String note;
}
