# APEX-3 · Dual-Domain Sync, Cross-Border Egress & Aggregate Disclosure Control

A platform for a multinational clinical trial that keeps China source records resident locally while letting a
global team use **de-identified derived data** only inside an approved purpose, field, site, observation-window and
protocol-version envelope. It implements the consent ledger, protocol catalogue, dual-domain eligibility
evaluator, watermark/back-fill coordination, snapshot delivery compiler, withdrawal propagator and post-outage
recovery verifier, together with the acceptance scenario for subjects A/B/C/D at analysis day 16.

On top of that it implements **aggregate disclosure control** for the safety-summary tables the global statistics
team requests (serious-AE counts by age band, centre group and protocol period). Because two individually lawful
summaries — adjacent windows, a one-centre difference, or a re-based historical version — can be subtracted to
isolate a small group, the platform adds a **release ledger, a cohort-overlap analyser, a differencing-attack
simulator, a disclosure-budget evaluator and a policy sandbox**. See §9.

Stack: **Vue 3 (Vite)** · **Spring Boot 3.5 / JDK** · **PostgreSQL**. Single page app is bundled into the Spring
Boot jar and served together with the REST API.

---

## 1. Quick start

### Option A — Docker Compose (one command)

```bash
cd /workspace/79
docker compose up --build
# open http://localhost:8080   (Log in as admin / demo123)
```

Compose builds the frontend, bundles it into the Spring Boot jar, starts PostgreSQL and the app, and seeds the
demo scenario on first boot. Health check: `curl http://localhost:8080/actuator/health`.

> The provided container in this workspace has **no Docker daemon**, so Compose was not executed here. The same
> image build was validated by running the packaged jar directly (Option B) plus the integration/UI test suites.

### Option B — Run locally (used for the verification below)

Prerequisites already present in this environment: `/opt/jdk-25`, `/opt/maven`, `/opt/node-22`, PostgreSQL on
`127.0.0.1:5432` (user/db `ccdev`), Redis not required.

```bash
# 1. database (already running in the workspace image)
psql "host=127.0.0.1 user=ccdev password=ccdev dbname=postgres" -c "CREATE DATABASE ctrial;"   # once

# 2. build the SPA, bundle it, and build the backend jar
cd frontend && npm install && npm run build && cd ..
cd backend  && mvn -DskipTests package

# 3. run
java -jar backend/target/ctrial-sync-1.0.0.jar
# open http://localhost:8080  — schema and demo data seed automatically on first start
```

Frontend developer mode (hot reload, proxies `/api` to :8080):

```bash
cd frontend && npm run dev      # http://localhost:5173
```

The backend reads `DB_URL`, `DB_USER`, `DB_PASSWORD`, `SERVER_PORT`, `DEMO_SEED` from the environment
(defaults in `backend/src/main/resources/application.yml`).

---

## 2. Test accounts (HTTP Basic)

All demo passwords are `demo123`. Roles are enforced server-side.

| Username | Role | Can do |
|----------|------|--------|
| `admin`    | ADMIN | everything |
| `global`   | GLOBAL_ANALYST (+AUDITOR) | compile cross-border deliveries, read snapshots |
| `china`    | CN_ANALYST | drive watermark / transfer / link operations |
| `auditor`  | AUDITOR | read-only audit, recovery, quarantine |
| `sponsor`  | SPONSOR | record consent & protocol versions |

Role checks that matter and were exercised: a China analyst gets **403** on `POST /api/deliveries/**`; an auditor
gets **403** on `POST /api/consents`.

---

## 3. How the domain model maps to the requirements

| Requirement | Where it lives |
|-------------|----------------|
| Consent ledger: subject, purpose, field group, region, validity interval | `ConsentEntry` — two clocks: `effectiveFrom`/`effectiveTo` (local signature) and `recordedAt` (receipt) |
| Consent validity uses the **local** signed/withdrawn instant, not receipt time | `EligibilityEngine.resolveConsent`, `EligibilityService.loadWorld` (filters on `effectiveFrom ≤ analysis`) |
| Protocol catalogue: version, approvals, effective time, applicable sites | `ProtocolVersion.fullyApproved/approvedAt/activeAt/appliesToSite` |
| Amendment applies only from explicit effective date, only after ethics **and** sponsor approval | `EligibilityEngine.resolveProtocol` |
| Local event ledger: stable id, event time, receipt time, revision chain, source, void | `LocalEvent` (unique on `(sourceSystem,domain,sourceSeq)` and `(stableId,revision)`; no direct identifiers) |
| No raw identity leaves China | `Subject.localIdentity` never projected; `DeliveryRecord` has only pseudonym + projected fields |
| Eligibility per record: 本地可用 / 全球可用 / 待定 + reasons | `Eligibility.LOCAL_AVAILABLE / GLOBAL_AVAILABLE / PENDING / BLOCKED` + `reasonCodes` |
| Intersection (not union) of consent, protocol, approval, purpose scope | `EligibilityEngine.evaluate` — sequential gates, any failure blocks, any undeterminable pends |
| Watermark coordinator: source-confirmed / transfer-ack / analysis, per domain | `Watermark` (three counters), `StateService`, `coverage` view |
| Gaps are UNCONFIRMED, never zero | `WATERMARK_UNCONFIRMED` reason + analysis cap; UI gap band |
| Snapshot compiler pins versions, member summary, field projection, filter, watermark | `DeliveryService.compile`, `Delivery` |
| Withdrawal propagator stops future queries, applies pre-registered retention/destruction | `RevocationService`, `DispositionRule`, `DispositionExecution`; historical delivery summary kept |
| Recovery verifier: gaps, duplicate re-sends, cross-domain digest agreement | `RecoveryService.run` → `RecoveryCheck` |
| Two reconstruction routes producing identical legal sets | `EligibilityRunService` (MATERIALIZED vs REPLAY) + equivalence test; both call one engine |
| Stale snapshots never authorise a new read; old deliveries not pretended erased | `invalidateRuns`, `Delivery.accessRevoked` with summary/digests intact |
| Retransmission after restore not double-counted | `EventIngestService` transport-identity dedupe + `TransportReceipt` |
| Withdrawal/correction race adjudicated by approved rule, else quarantined | `EventIngestService` + `TieBreakRule` + `QuarantineItem` |

### Reconstruction routes

* **MATERIALIZED** — `POST /api/runs/materialize?day=16` evaluates once and persists one row per candidate. Later
  consent/protocol revisions mark affected runs `STALE`. Queries read the stored rows directly; a stale run is
  never consulted to authorise a read.
* **REPLAY** — `GET /api/runs/replay?day=16` rebuilds from the append-only ledgers at the chosen instant,
  persisting nothing; it requires the watermark completeness proof returned alongside.

Both call the same pure `EligibilityEngine`. The acceptance check `ALL/EQ` and `GET /api/runs/compare?day=16`
assert the two routes produce the same legal membership, verdicts and reason codes. At day 16 both report
**13 legal rows, 0 differences**.

---

## 4. The acceptance scenario (analysis day 16)

Study day 1 = `2026-03-02T09:00Z`; analysis instant for day N = `23:00Z` on that day.
Sites: `CN-01` Shanghai (healthy), `CN-02` Wuhan (link stalled), `SG-01` Singapore (healthy).

| Subject | Timeline | Required outcome |
|---------|----------|------------------|
| **A** | signs China-only consent D10; visit occurs D11; uploaded D15 | **not** global-available (reason = consent scope, not upload delay); locally available |
| **B** | AE occurs D11; withdraws global purpose D12 (signed 09:00, received 20:00); new visit D13 | D11 AE held under restricted retention but reuse globally blocked; D13 visit has no basis; local use continues |
| **C** | protocol v2.0 approved D13, effective D14 at CN-01; imaging D13 and D15 | D13 under v1.2; D15 under v2.0 carrying `IMAGING_ADC`/`IMAGING_TUMOR_VOL`; v2.0 never applies to other sites |
| **D** | CN-02 link stalled after D9; randomisation D5, visit D12 | D5 globally available; D12 **PENDING / UNCONFIRMED** globally, locally available; gap shown, not zero |

If a historical delivery contains B's D11 row (e.g. compile at day 11), propagating B's withdrawal revokes
access to it immediately and applies `DR-RETAIN-25Y` (restricted retention) while the delivery's frozen member
summary and digests stay intact for audit — the delivery is not pretended to have been erased.

---

## 5. Verification (what was actually run here)

All commands below were executed in this workspace against a live instance. Results are quoted verbatim.

### 5.1 Backend integration tests — 20/20 pass

```bash
cd backend && mvn test
```
```
Tests run: 15, Failures: 0, Errors: 0, Skipped: 0   (AcceptanceRulesTest — dual-domain rules)
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0   (DisclosureRulesTest — aggregate disclosure)
Tests run: 20, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```
Covers: A/B/C/D rules; intersection semantics; both reconstruction routes agreeing (read-only and persisted);
stale-snapshot invalidation; transport dedupe; conflicting-duplicate quarantine; withdrawal/correction race
quarantine; delivery projection has no identity fields and survives revocation as an intact summary. Plus the
disclosure rules in §9.4.

### 5.2 Frontend render tests (live backend) — 8/8 pass

```bash
cd frontend && npm run test        # vitest + happy-dom, mounts the real views against http://127.0.0.1:8080
```
```
Test Files  1 passed (1)
     Tests  8 passed (8)
```
Asserts the Overview shows **15/15**, the Acceptance cards list A1/B1/C1/D2, the Eligibility pivot renders both
planes and subject D as Pending, and the Consent / Deliveries / Watermarks / Disclosure views render without error.

### 5.3 End-to-end API acceptance (live jar)

| Step | Result |
|------|--------|
| `GET /api/acceptance?day=16` | **15/15 acceptance checks pass** (A1–A3, B1–B5, C1–C3, D1–D3, EQ) |
| `POST /api/deliveries/compile {day:11}` | `DLV-D11-001`, 2 members incl. `PSN-B2`, digest `a5110f88…` |
| `POST /api/deliveries/compile {day:16, route:REPLAY}` | `DLV-D16-001`, 4 members (C×3, D randomisation) |
| `POST /api/revocations/propagate {consentEntryId:3}` | 2 snapshot runs invalidated, 1 delivery access-revoked, 1 row restricted under `DR-RETAIN-25Y` |
| `GET /api/deliveries/DLV-D11-001` after revocation | `readable=false`, `status=ACCESS_REVOKED`, content digest intact, 2 summary lines kept |
| `GET /api/deliveries/DLV-D16-001` | `readable=true`, `status=ACTIVE` (unaffected) |
| `POST /api/recovery/run {site:CN-02}` | `status=FINDINGS`, 2 gaps, 0 duplicate resends, 0 digest mismatches |
| exact re-send of `VST-D4-V1` seq 12 | `duplicate=true` — "Retransmission … acknowledged; counted once." event count stays 9 |
| conflicting re-send (different payload) | `duplicate=true, quarantined=true` → `Q-0001-DUP` |
| `GET /api/watermarks?day=16` | CN-02 source confirmed 12 / ack 9 / analysis 9 → D12 **UNCONFIRMED** |

The pinned watermark string for day 16 is
`CN-01:AE=15(src=15,ack=15); … CN-02:VISIT=12(src=12,ack=9)…` (the CN-02 analysis stays at 9).

---

## 6. Using the console

Open `http://localhost:8080` and sign in (the login screen lists the demo accounts; click one to prefill).

* **Overview** — acceptance score, watermark gaps, deliveries, subjects.
* **Acceptance** — the four subject cards with expected vs observed and reason codes.
* **Consent & protocol** — append grants/withdrawals and protocol versions; per-subject timelines; approved
  envelopes. Recording a withdrawal invalidates affected snapshot runs immediately.
* **Eligibility** — the dual-plane pivot (replay or materialised), with a live route-equivalence badge and the
  source event ledger. The top-bar **analysis day** changes the instant for every screen.
* **Watermarks** — per-domain three-counter bars with an UNCONFIRMED gap band; confirm source / ack transfer /
  fold analysis / break & restore link; run a recovery check.
* **Deliveries** — compile by either route, inspect the pinned envelope, frozen member summary and projected
  records; revocation state is visible.
* **Withdrawal** — propagate a recorded withdrawal, register disposition and tie-break rules, see executions.
* **Recovery** — recovery reports (expand for per-domain digests), simulate a post-restore re-send, resolve
  quarantined races.
* **Disclosure control** — the aggregate release ledger, relation graph, overlap/differencing analyser,
  differencing-attack simulator, budget trajectory and policy sandbox (see §9).
* **Audit** — the append-only audit trail of every regulator-relevant transition.

### Try the headline flow

1. As **global**, Deliveries → set analysis day `11` → *Compile delivery* (contains B's D11 AE).
2. As **admin**, Withdrawal → *Propagate* B's withdrawal.
3. Back in Deliveries → open `DLV-D11-001`: content is revoked, but the frozen summary and digests remain.
4. Set day `16`, Acceptances → all checks pass; Eligibility → D's D12 visit is Pending globally, available locally.

---

## 7. Design decisions worth noting

* **One engine, two routes.** Materialisation is a cache over the same `EligibilityEngine`; it can never diverge
  because it is not a second implementation. Snapshots are `STALE`d on later changes and are refused for new
  reads, so an old delivery is never a standing authorisation.
* **Intersection, literally.** Every gate must pass; a single failing gate blocks, a single undeterminable gate
  pends. There is no union anywhere in the evaluator.
* **Two clocks, on purpose.** All validity uses the local signature/withdrawal instant; the receipt instant is
  only an ordering tie-breaker. B's withdrawal is effective at the moment it was signed, not when it reached us.
* **Gaps are first-class.** The analysis watermark can never fold past the transfer acknowledgement, and anything
  above it is `PENDING`, never reported as absent. The UI draws the unconfirmed band instead of a zero.
* **History is preserved.** Revoking a delivery sets `accessRevoked` and applies the registered disposition, but
  the legal-member summary and digests are frozen and kept. Nothing claims the delivery never existed.
* **Isolate, don't guess.** A withdrawal/correction race with equal effective instants and no registered
  tie-break rule is quarantined; it never silently picks a side.

---

## 8. Project layout

```
backend/
  src/main/java/com/ctrial/
    model/        JPA entities + Enums (the rule vocabulary)
    service/      EligibilityEngine (pure), EligibilityService, EligibilityRunService,
                  DeliveryService, RevocationService, RecoveryService, EventIngestService,
                  StateService, AcceptanceService, AuditService
    disclosure/   Aggregate disclosure control:
                    CellSpace, AggregationEngine, QuerySpec      (local aggregation + suppression)
                    AttackerModel, IntervalSolver                (public-only LP over the ledger)
                    OverlapAnalyzer, ReleaseLedger, ReleaseSnapshot
                    BudgetEvaluator, ConstraintTracker, AttackSimulator, PolicySandbox, PublishGate
    web/          ReadController, CommandController, DisclosureController, SpaConfig
    config/       SecurityConfig, DemoSeeder, DisclosureSeeder
  src/test/java/com/ctrial/AcceptanceRulesTest.java       (15 dual-domain rules)
  src/test/java/com/ctrial/DisclosureRulesTest.java        (5 disclosure-control rules)
frontend/
  src/            App, views/* (incl. Disclosure.vue), components/*, lib/api.js, lib/store.js, ui.test.js
Dockerfile, compose.yaml
```

---

## 9. Aggregate disclosure control

The global statistics team requests summary tables — serious-AE **counts** by age band, centre group and protocol
period — at different weeks. Each table may individually satisfy the minimum-group rule, yet two lawful tables can
be **subtracted** (adjacent windows, a one-centre difference, or a re-based historical version) to isolate a small
group. This layer governs the *relations between* summaries, not just each summary's fields.

### 9.1 The pieces

| Component | What it does | Where |
|-----------|--------------|-------|
| **Analysis surfaces** | Freeze purpose, protocol versions, fields, observation window, watermark day and the **age recode** for a table. Two tables on one surface are comparable by construction. | `DisclosureSurface`, `DisclosureSeeder` |
| **Aggregate engine** | Rolls local, de-identified safety subjects up into cells; tests the **enrolled denominator** against the minimum group; applies suppression first, then rounding / perturbation **only to cells that already cleared the floor**. | `AggregationEngine` |
| **Release ledger** | Records every request — published **or** blocked — with its frozen surface, grouping dimensions, canonical filter, denominator scope, suppressed cells, mechanism, result version and budget. Stores **aggregates + a one-way membership digest**, never subject-level members. | `DisclosureRelease`, `DisclosureCell`, `ReleaseLedger` |
| **Relation graph** | Directed edges between releases: SAME_SURFACE, NESTED_SUBSET, SUPERSET_VERSION, RECODED_DIMENSION, DUPLICATE, SUBTRACTIVE_RISK, OVERLAPPING, DISJOINT — plus COMPARABILITY edges for recoded surfaces. | `OverlapAnalyzer`, `ReleaseRelation` |
| **Overlap analyser** | Computes containment / intersection / **difference** of cohorts locally and flags any pair whose difference isolates a residual cell below the floor. Deterministic rounding is treated as **exact** (it cancels in a subtraction — never credited as protection). | `OverlapAnalyzer` |
| **Attack simulator** | Builds the attacker's world from **published tables + public enrolment + public totals only** and, via linear programming, enumerates each *suppressed* group's derivable count interval. Never reads local unpublished detail. | `AttackerModel`, `IntervalSolver`, `AttackSimulator` |
| **Constraint tracker** | Connected components of releases sharing atoms; records each suppressed group an attacker can pin or narrow to width ≤ 1 as a finding. | `ConstraintTracker`, `ConstraintFinding` |
| **Budget evaluator** | Merges cumulative loss on a face and distinguishes **NEW_QUERY**, **NEW_DATA_INCREMENT** (later watermark re-charges overlapping members — a version is *not* a new population), **DUPLICATE_QUERY** and **DEFINITION_CHANGE** (a recode opens a comparability edge instead of reusing budget). | `BudgetEvaluator`, `BudgetTrace` |
| **Policy sandbox** | Shows, for a request, the effect of **defer / merge cells / consistent perturbation / refuse** on which questions are answerable and what intervals remain inferable. | `PolicySandbox` |
| **Publish gate** | Refuses a request when **any** of: its own suppressed group becomes recoverable, a same-surface one-centre difference would isolate it, or the cumulative budget would exceed its cap. Every request is written to the ledger either way. | `PublishGate` |

### 9.2 The scenarios it must catch (all exercised)

1. **One-centre difference with identical deterministic rounding** — two same-shape tables on one surface that
   differ only in CN-02 are marked **SUBTRACTIVE_RISK**; the analyser notes "rounding cancels and does NOT protect".
   Seeded as `REL-BASE-001` (all sites) vs `REL-BASE-002` (without CN-02).
2. **Suppressed cell + public total** — a period-level table suppresses CN-02 age-C cells (denominator 2/2/1 < 5),
   but the public `CN-02|P1` total pins `CN-02|C|P1` to **exactly 1**. The simulator exposes it and the gate refuses
   the request. Seeded cautionary baseline: `REL-BASE-LEGACY` (a pre-gate table that leaked); any *new* period-level
   request on the same surface is now **BLOCKED**.
3. **Watermark increment is not a new population** — a same-shape table on a later-day surface (V1→V2) is classified
   **NEW_DATA_INCREMENT**; the overlapping members are re-charged, and the simulator links calendar versions with
   monotone (non-decreasing) constraints so a later table is an increment, never an independent cohort.
4. **Purpose recode → comparability edge** — `SURF-SAFETY-V3` folds ages into `18-64`/`65+`; its releases carry
   `comparableTo=SURF-SAFETY-V2` and are classified **DEFINITION_CHANGE**, never reusing V2's budget.

### 9.3 REST API (read for any role; publish = global/admin)

| Method | Path | Purpose |
|--------|------|---------|
| GET | `/api/disclosure/surfaces` | Frozen surfaces incl. public totals and comparability edges |
| GET | `/api/disclosure/releases`, `/releases/{code}` | Ledger and one entry incl. cells |
| GET | `/api/disclosure/graph` | Nodes + typed edges (incl. comparability) |
| GET | `/api/disclosure/overlap` | Pairwise relations, subtractive pairs, constraint findings |
| GET | `/api/disclosure/budget` | Budget traces and cumulative loss per lineage |
| GET | `/api/disclosure/simulations` | Persisted attack simulations |
| POST | `/api/disclosure/simulate` | Run + persist a differencing simulation |
| POST | `/api/disclosure/preview` · `/publish` | Dry-run / commit a request through the gate |
| POST | `/api/disclosure/sandbox` | Defer / merge / perturb / refuse comparison |

### 9.4 Verification (executed in this workspace against a live instance)

Backend integration tests — **20/20 pass** (`mvn test`): the existing 15 dual-domain rules plus 5 disclosure rules
(`DisclosureRulesTest`): period-pooled cells clear the floor while period-level CN-02 age-C cells are suppressed;
the public-total join recovers a suppressed group and is blocked; a one-centre difference under deterministic
rounding is `SUBTRACTIVE_RISK`; a later watermark is `NEW_DATA_INCREMENT` with repeated members re-charged; the
recoded surface opens a comparability edge.

Live end-to-end (cap 3.0):

| Step | Result |
|------|--------|
| `POST /api/disclosure/publish` period-level FINE on V1 | **blocked=true** — "a suppressed small group of this request is pinned by public information alone" |
| publish pooled age×centre FINE on V1 | **RELEASED** (e.g. `REL-SAFETY-COHORT-2026-004`), 9 cells, 0 suppressed |
| publish the same shape on V2 (later watermark) | **RELEASED**, `budgetKind=NEW_DATA_INCREMENT` |
| publish a pool on the recoded V3 | **RELEASED**, `comparableTo=SURF-SAFETY-V2`, `DEFINITION_CHANGE` |
| `GET /api/disclosure/overlap` | `subtractivePairs=1` (REL-BASE-001 ⊕ REL-BASE-002), 1 constraint finding `[1,1]` |
| `POST /api/disclosure/simulate {surfaceCode: SURF-SAFETY-V1}` | BLOCKED — `AGE_BAND=C;CENTER_GROUP=CN-02;PERIOD=P1 ∈ [1,1]` |
| `POST /api/disclosure/sandbox` (period-level FINE, V1) | PUBLISH_AS_IS withheld; MERGE / PERTURB answerable; REFUSE withholds all |

Frontend render tests — **8/8 pass** (`npm run test`, live backend), including the new Disclosure view rendering the
surfaces, relation graph, ledger and sandbox.

