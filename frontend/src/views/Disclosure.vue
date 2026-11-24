<script setup>
import { onMounted, ref, computed } from 'vue'
import { api } from '../lib/api.js'
import { store, toast } from '../lib/store.js'

const loading = ref(true)
const surfaces = ref([])
const releases = ref([])
const graph = ref({ nodes: [], edges: [] })
const overlap = ref({ pairs: [], constraintFindings: [] })
const simulations = ref([])
const budgetData = ref({ traces: [], cumulativeByLineage: {} })
const lastSim = ref(null)
const selectedRelease = ref(null)
const releaseDetail = ref(null)

const sandboxSurface = ref('')
const sandboxDims = ref(['AGE_BAND', 'CENTER_GROUP'])
const sandboxGran = ref('FINE')
const sandboxMechanism = ref('SUPPRESSION')
const sandboxCap = ref(3.0)
const sandboxReport = ref(null)
const busy = ref(false)

const pubSurface = ref('')
const pubDims = ref(['AGE_BAND', 'CENTER_GROUP'])
const pubGran = ref('FINE')
const pubMechanism = ref('SUPPRESSION')
const pubCap = ref(3.0)
const publishPreview = ref(null)

const dimOptions = ['AGE_BAND', 'CENTER_GROUP', 'PERIOD']

onMounted(async () => {
  await load()
})

async function load() {
  loading.value = true
  try {
    const [s, r, g, o, sims, b] = await Promise.all([
      api.get('/disclosure/surfaces'),
      api.get('/disclosure/releases'),
      api.get('/disclosure/graph'),
      api.get('/disclosure/overlap'),
      api.get('/disclosure/simulations'),
      api.get('/disclosure/budget')
    ])
    surfaces.value = s
    releases.value = r
    graph.value = g
    overlap.value = o
    simulations.value = sims
    budgetData.value = b
    if (!sandboxSurface.value && s.length) sandboxSurface.value = s.find(x => x.status === 'ACTIVE')?.code || s[0].code
    if (!pubSurface.value && s.length) pubSurface.value = s.find(x => x.status === 'ACTIVE')?.code || s[0].code
  } finally {
    loading.value = false
  }
}

async function openRelease(code) {
  selectedRelease.value = code
  releaseDetail.value = await api.get(`/disclosure/releases/${code}`)
}

async function runSim() {
  busy.value = true
  try {
    const res = await api.post('/disclosure/simulate', { surfaceCode: sandboxSurface.value })
    lastSim.value = res
    toast(`Simulation ${res.code}: ${res.verdict.slice(0, 48)}…`, res.blocked ? 'bad' : 'ok')
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function runSandbox() {
  busy.value = true
  try {
    sandboxReport.value = await api.post('/disclosure/sandbox', {
      surfaceCode: sandboxSurface.value,
      dimensions: sandboxDims.value,
      centerGranularity: sandboxGran.value,
      mechanism: sandboxMechanism.value,
      budgetCap: Number(sandboxCap.value)
    })
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function previewPublish() {
  busy.value = true
  try {
    publishPreview.value = await api.post('/disclosure/preview', {
      surfaceCode: pubSurface.value,
      dimensions: pubDims.value,
      centerGranularity: pubGran.value,
      mechanism: pubMechanism.value,
      budgetCap: Number(pubCap.value)
    })
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

async function doPublish() {
  busy.value = true
  try {
    const res = await api.post('/disclosure/publish', {
      surfaceCode: pubSurface.value,
      dimensions: pubDims.value,
      centerGranularity: pubGran.value,
      mechanism: pubMechanism.value,
      budgetCap: Number(pubCap.value)
    })
    const blocked = res.blocked
    toast(`${res.release.code} ${blocked ? 'BLOCKED' : 'RELEASED'}`, blocked ? 'bad' : 'ok')
    publishPreview.value = res.preview
    await load()
  } catch (e) {
    toast(e.message, 'bad')
  } finally {
    busy.value = false
  }
}

function toggle(arr, v) {
  const i = arr.value.indexOf(v)
  if (i >= 0) arr.value.splice(i, 1)
  else arr.value.push(v)
}

function statusCls(s) {
  return { RELEASED: 'ok', BLOCKED: 'bad', DEFERRED: 'warn', MERGED: 'ok', SANDBOX: 'muted' }[s] || 'muted'
}
function riskCls(r) {
  return { NONE: 'muted', LOW: 'ok', MEDIUM: 'warn', HIGH: 'bad', BLOCKED: 'bad' }[r] || 'muted'
}
function fmt(dt) {
  if (!dt) return '—'
  return new Date(dt).toISOString().replace('T', ' ').slice(0, 16)
}

const lineageBudget = computed(() => {
  const m = budgetData.value.cumulativeByLineage || {}
  return Object.entries(m).map(([k, v]) => ({ lineage: k, loss: Math.round(v * 1000) / 1000 }))
})
const worstEdges = computed(() =>
  [...graph.value.edges].filter(e => e.type === 'SUBTRACTIVE_RISK' || e.type === 'COMPARABILITY' || e.risk === 'HIGH'))
const leakedIntervals = computed(() => (lastSim.value?.intervals || []).filter(i => i.leaked))
</script>

<template>
  <div>
    <div class="page-head">
      <h1>Aggregate disclosure control</h1>
      <p class="lede">
        Every aggregate summary is released against a frozen analysis surface and tracked as a ledger entry — never a
        subject-level export. Even when each table clears the minimum-group rule, the relation graph and the
        differencing simulator check whether two lawful tables can be subtracted to isolate a small group. Requests
        that would push a constraint group below the risk floor are refused, not merely suppressed.
      </p>
    </div>

    <div v-if="loading" class="empty">Loading disclosure ledger…</div>
    <div v-else class="stack">

      <!-- Summary stats -->
      <div class="grid cols-4">
        <div class="stat">
          <div class="label">Surfaces</div>
          <div class="value">{{ surfaces.length }}</div>
          <div class="foot">{{ surfaces.filter(s => s.status === 'RECODED').length }} recoded · {{ surfaces.filter(s => s.comparableOf).length }} comparability edges</div>
        </div>
        <div class="stat">
          <div class="label">Ledger entries</div>
          <div class="value">{{ releases.length }}</div>
          <div class="foot">{{ releases.filter(r => r.status === 'RELEASED').length }} released · {{ releases.filter(r => r.status === 'BLOCKED').length }} blocked</div>
        </div>
        <div class="stat">
          <div class="label">Subtractive pairs</div>
          <div class="value" :style="{ color: overlap.subtractivePairs ? 'var(--bad)' : 'var(--ok)' }">{{ overlap.subtractivePairs }}</div>
          <div class="foot">same surface, one-centre difference</div>
        </div>
        <div class="stat">
          <div class="label">Constraint findings</div>
          <div class="value" :style="{ color: overlap.constraintFindings.length ? 'var(--bad)' : 'var(--ok)' }">{{ overlap.constraintFindings.length }}</div>
          <div class="foot">cells pinned below the risk floor</div>
        </div>
      </div>

      <!-- Frozen surfaces -->
      <section class="panel">
        <div class="panel-head"><h2>Frozen analysis surfaces</h2><div class="spacer"></div>
          <span class="small faint">a surface pins purpose, protocol, windows, watermark and age recode</span></div>
        <div class="panel-body tight">
          <table>
            <thead><tr><th>Surface</th><th>v</th><th>Day</th><th>Age recode</th><th>Status</th><th>Comparable to</th><th>Public totals</th></tr></thead>
            <tbody>
              <tr v-for="s in surfaces" :key="s.code">
                <td class="mono">{{ s.code }}</td>
                <td class="num">{{ s.version }}</td>
                <td class="num">D{{ s.analysisDay }}</td>
                <td class="small">{{ s.ageRecode }}</td>
                <td><span class="badge" :class="s.status === 'ACTIVE' ? 'ok' : s.status === 'RECODED' ? 'warn' : 'muted'">{{ s.status }}</span></td>
                <td class="small">
                  <span v-if="s.comparableOf" class="mono-sm">{{ s.comparableOf }}</span>
                  <span v-else class="faint">—</span>
                </td>
                <td class="mono-sm faint">{{ s.publicTotals || s.publicEnrollment }}</td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="surfaces.some(s => s.comparableOf)" class="panel-body" style="border-top:1px solid var(--line-soft)">
          <div v-for="s in surfaces.filter(x => x.comparableOf)" :key="s.code" class="notice pend small" style="margin-bottom:6px">
            <strong>{{ s.code }} ↔ {{ s.comparableOf }}</strong> — {{ s.comparabilityNote }}
          </div>
        </div>
      </section>

      <!-- Relation graph -->
      <div class="grid cols-2">
        <section class="panel">
          <div class="panel-head"><h2>Release relation graph</h2><div class="spacer"></div>
            <span class="small faint">{{ graph.nodes.length }} nodes · {{ graph.edges.length }} edges</span></div>
          <div class="panel-body">
            <div v-if="!graph.edges.length" class="empty">No relations yet.</div>
            <div v-else class="stack">
              <div v-for="(e, i) in graph.edges" :key="i" class="rel-edge">
                <div class="row" style="gap:8px">
                  <span class="chip">{{ e.from }}</span>
                  <span class="faint">→</span>
                  <span class="chip">{{ e.to }}</span>
                  <span class="badge" :class="riskCls(e.risk)" style="margin-left:auto">{{ e.risk }}</span>
                </div>
                <div class="row" style="gap:8px; margin-top:4px">
                  <span class="badge" :class="e.type === 'SUBTRACTIVE_RISK' ? 'bad' : e.type === 'COMPARABILITY' ? 'pend' : 'muted'">{{ e.type }}</span>
                  <span v-if="e.overlap !== undefined" class="small faint">overlap {{ Math.round(e.overlap * 100) }}%</span>
                </div>
                <div class="mono-sm faint" style="margin-top:4px">{{ e.detail }}</div>
              </div>
            </div>
          </div>
        </section>

        <!-- Overlap / differencing analyser -->
        <section class="panel">
          <div class="panel-head"><h2>Overlap &amp; differencing analyser</h2><div class="spacer"></div>
            <span class="small faint">local-domain only</span></div>
          <div class="panel-body scroll-y">
            <div v-if="!overlap.pairs.length" class="empty">No paired releases to compare.</div>
            <div v-else class="stack">
              <div v-for="(p, i) in overlap.pairs" :key="i" class="pair-row"
                   :style="p.type === 'SUBTRACTIVE_RISK' ? 'border-color: rgba(248,81,73,0.4)' : ''">
                <div class="row">
                  <strong class="mono-sm">{{ p.a }} ⊕ {{ p.b }}</strong>
                  <span class="badge" :class="riskCls(p.risk)" style="margin-left:auto">{{ p.type }}</span>
                </div>
                <div class="small faint" style="margin-top:4px">
                  overlap {{ Math.round(p.overlap * 100) }}% · {{ p.sharedAtoms }} shared atoms
                  <span v-if="p.smallestResidualDenominator"> · smallest residual denom {{ p.smallestResidualDenominator }}</span>
                </div>
                <div v-if="p.subtractiveCells?.length" class="notice bad small" style="margin-top:6px">
                  Difference isolates below the floor: {{ p.subtractiveCells.join('; ') }}
                </div>
              </div>
            </div>
          </div>
        </section>
      </div>

      <!-- Constraint-group findings -->
      <section class="panel">
        <div class="panel-head"><h2>Constraint-group tracking</h2><div class="spacer"></div>
          <span class="small faint">cells an attacker can pin from the combined ledger</span></div>
        <div class="panel-body tight">
          <div v-if="!overlap.constraintFindings.length" class="empty">
            No cell is pinned below the risk floor by the current ledger.
          </div>
          <table v-else>
            <thead><tr><th>Cell</th><th>Interval</th><th>Width</th><th>Denom</th><th>Risk</th><th>Basis</th></tr></thead>
            <tbody>
              <tr v-for="(f, i) in overlap.constraintFindings" :key="i">
                <td class="mono-sm">{{ f.cellKey }}</td>
                <td class="num">[{{ f.lowerBound }}, {{ f.upperBound }}]</td>
                <td class="num">{{ f.width }}</td>
                <td class="num">{{ f.denominator }}</td>
                <td><span class="badge" :class="riskCls(f.risk)">{{ f.risk }}</span></td>
                <td class="small faint">{{ f.basis }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Attack simulator -->
      <section class="panel">
        <div class="panel-head"><h2>Differencing attack simulator</h2><div class="spacer"></div>
          <select v-model="sandboxSurface" style="margin-right:8px">
            <option v-for="s in surfaces" :key="s.code" :value="s.code">{{ s.code }}</option>
          </select>
          <button class="primary sm" :disabled="busy" @click="runSim">Simulate</button>
        </div>
        <div class="panel-body">
          <div class="notice small" style="margin-bottom:12px">
            The simulator knows only the released tables, the public enrolment scale and any public total on the
            surface. It never reads local unpublished detail; an interval that collapses here collapsed from public
            information alone. Targets are the <em>suppressed</em> small groups.
          </div>
          <div v-if="lastSim">
            <div class="row" style="margin-bottom:8px">
              <span class="badge" :class="lastSim.blocked ? 'bad' : 'ok'">{{ lastSim.blocked ? 'BLOCKED' : 'SAFE' }}</span>
              <span class="small">{{ lastSim.verdict }}</span>
            </div>
            <div class="small faint mono-sm" style="margin-bottom:8px">{{ lastSim.knowledge }} · {{ lastSim.variables }} vars · {{ lastSim.equations }} equations · {{ lastSim.targets }} targets</div>
            <table v-if="lastSim.intervals.length">
              <thead><tr><th>Suppressed group</th><th>Derivable interval</th><th>Width</th><th>Denom</th><th>Risk</th></tr></thead>
              <tbody>
                <tr v-for="(iv, i) in lastSim.intervals" :key="i"
                    :style="iv.leaked ? 'background: rgba(248,81,73,0.06)' : ''">
                  <td class="mono-sm">{{ iv.cellKey }}</td>
                  <td class="num">[{{ Math.round(iv.lo) }}, {{ Math.round(iv.hi) }}]</td>
                  <td class="num">{{ Math.round(iv.width) }}</td>
                  <td class="num">{{ iv.denominator }}</td>
                  <td><span class="badge" :class="riskCls(iv.risk)">{{ iv.leaked ? 'LEAKED' : 'protected' }}</span></td>
                </tr>
              </tbody>
            </table>
            <div v-else class="empty">No suppressed groups in view — nothing to recover.</div>
          </div>
          <div v-else class="empty">Run a simulation to enumerate the recoverable count intervals.</div>
        </div>
        <div v-if="simulations.length" class="panel-body tight" style="border-top:1px solid var(--line-soft)">
          <table>
            <thead><tr><th>Sim</th><th>Scope</th><th>Vars</th><th>Eqs</th><th>Targets</th><th>Pinned</th><th>Verdict</th><th>At</th></tr></thead>
            <tbody>
              <tr v-for="s in simulations.slice(0, 6)" :key="s.code">
                <td class="mono-sm">{{ s.code }}</td>
                <td class="small">{{ s.scope }}</td>
                <td class="num">{{ s.variables }}</td>
                <td class="num">{{ s.equations }}</td>
                <td class="num">{{ s.targetsAnalyzed }}</td>
                <td class="num" :style="{ color: s.exactCells ? 'var(--bad)' : 'inherit' }">{{ s.exactCells }}</td>
                <td class="small" :class="s.blocked ? 'bad' : 'faint'">{{ s.verdict.slice(0, 42) }}</td>
                <td class="small faint">{{ fmt(s.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Budget trajectory -->
      <section class="panel">
        <div class="panel-head"><h2>Disclosure budget trajectory</h2><div class="spacer"></div>
          <span class="small faint">versions re-charge overlap; recodes open a new edge</span></div>
        <div class="panel-body">
          <div class="row wrap" style="gap:20px; margin-bottom:12px">
            <div v-for="b in lineageBudget" :key="b.lineage" class="small">
              <span class="faint">{{ b.lineage }}</span>
              <span class="num" style="margin-left:6px">Σ {{ b.loss }}</span>
            </div>
          </div>
          <div v-if="!budgetData.traces.length" class="empty">No budget consumed yet.</div>
          <table v-else>
            <thead><tr><th>#</th><th>Release</th><th>Kind</th><th>New</th><th>Repeated</th><th>Overlap</th><th>Δε</th><th>Σε</th><th>Reason</th></tr></thead>
            <tbody>
              <tr v-for="t in budgetData.traces" :key="t.seq + t.releaseCode + t.at">
                <td class="num">{{ t.seq }}</td>
                <td class="mono-sm">{{ t.releaseCode }}</td>
                <td><span class="badge" :class="t.kind === 'NEW_DATA_INCREMENT' ? 'warn' : t.kind === 'DUPLICATE_QUERY' ? 'muted' : 'local'">{{ t.kind }}</span></td>
                <td class="num">{{ t.newMembers }}</td>
                <td class="num">{{ t.repeatedMembers }}</td>
                <td class="num">{{ Math.round(t.overlapFraction * 100) }}%</td>
                <td class="num">{{ t.incrementalLoss }}</td>
                <td class="num">{{ t.cumulativeLoss }}</td>
                <td class="small faint" style="max-width:280px">{{ t.reason }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Ledger -->
      <section class="panel">
        <div class="panel-head"><h2>Aggregate release ledger</h2><div class="spacer"></div>
          <span class="small faint">aggregates + membership digest only — no subject-level members</span></div>
        <div class="panel-body tight">
          <table>
            <thead><tr><th>Code</th><th>Surface</th><th>Dimensions</th><th>Gran.</th><th>Mechanism</th><th>Pub/Sup</th><th>Kind</th><th>Δε</th><th>Status</th></tr></thead>
            <tbody>
              <tr v-for="r in releases" :key="r.code" @click="openRelease(r.code)" style="cursor:pointer"
                  :style="selectedRelease === r.code ? 'background: rgba(88,166,255,0.06)' : ''">
                <td class="mono-sm">{{ r.code }}</td>
                <td class="small">{{ r.surface }}</td>
                <td class="small">{{ r.dimensions }}</td>
                <td class="small">{{ r.centerGranularity }}</td>
                <td class="small">{{ r.mechanism }}</td>
                <td class="num">{{ r.publishedCells }}/{{ r.suppressedCells }}</td>
                <td><span class="badge" :class="r.budgetKind === 'NEW_DATA_INCREMENT' ? 'warn' : r.budgetKind === 'DUPLICATE_QUERY' ? 'muted' : 'local'">{{ r.budgetKind }}</span></td>
                <td class="num">{{ r.incrementalLoss }}</td>
                <td><span class="badge" :class="statusCls(r.status)">{{ r.status }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div v-if="releaseDetail" class="panel-body" style="border-top:1px solid var(--line-soft)">
          <div class="row"><h3 class="mono">{{ releaseDetail.code }}</h3>
            <span class="badge" :class="statusCls(releaseDetail.status)">{{ releaseDetail.status }}</span>
            <div class="spacer"></div>
            <button class="sm ghost" @click="releaseDetail = null">Close</button></div>
          <dl class="kv" style="margin-top:10px">
            <dt>Surface / version</dt><dd>{{ releaseDetail.surface }} · {{ releaseDetail.resultVersion }}</dd>
            <dt>Frozen filter</dt><dd class="mono-sm">{{ releaseDetail.filterCanonical }} · {{ releaseDetail.denominatorScope }}</dd>
            <dt>Mechanism</dt><dd class="small">{{ releaseDetail.mechanism }} — {{ releaseDetail.mechanismDetail }}</dd>
            <dt>Members (digest)</dt><dd class="mono-sm">{{ releaseDetail.memberCount }} · {{ releaseDetail.membershipDigest?.slice(0,24) }}…</dd>
            <dt>Budget</dt><dd class="small">{{ releaseDetail.budgetKind }} · Δε {{ releaseDetail.incrementalLoss }} · Σε {{ releaseDetail.cumulativeLoss }}/{{ releaseDetail.budgetCap }}</dd>
            <dt v-if="releaseDetail.blockedReason">Blocked</dt>
            <dd v-if="releaseDetail.blockedReason" class="small bad">{{ releaseDetail.blockedReason }}</dd>
          </dl>
          <div class="divider"></div>
          <div class="small faint" style="margin-bottom:4px">Published / suppressed cells (aggregates only)</div>
          <table>
            <thead><tr><th>Cell</th><th>True</th><th>Published</th><th>Denom</th><th>Status</th></tr></thead>
            <tbody>
              <tr v-for="c in releaseDetail.cells" :key="c.dimKey">
                <td class="mono-sm">{{ c.label }}</td>
                <td class="num">{{ c.trueValue }}</td>
                <td class="num">{{ c.suppressed ? '—' : c.publishedValue }}</td>
                <td class="num">{{ c.denominator }}</td>
                <td><span class="badge" :class="c.suppressed ? 'bad' : 'ok'">{{ c.suppressed ? c.suppressionReason : 'published' }}</span></td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Policy sandbox -->
      <section class="panel">
        <div class="panel-head"><h2>Policy sandbox</h2><div class="spacer"></div>
          <span class="small faint">compare defer / merge / perturb / refuse before committing</span></div>
        <div class="panel-body">
          <div class="row wrap" style="gap:12px; margin-bottom:12px">
            <label class="field" style="width:200px"><span>Surface</span>
              <select v-model="sandboxSurface">
                <option v-for="s in surfaces" :key="s.code" :value="s.code">{{ s.code }}</option>
              </select>
            </label>
            <div class="field"><span>Dimensions</span>
              <div class="row" style="gap:6px">
                <button v-for="d in dimOptions" :key="d" class="sm"
                        :class="sandboxDims.includes(d) ? 'primary' : ''" @click="toggle(sandboxDims, d)">{{ d }}</button>
              </div>
            </div>
            <label class="field" style="width:140px"><span>Centres</span>
              <select v-model="sandboxGran"><option value="COARSE">Coarse (CN/SG)</option><option value="FINE">Fine (per site)</option></select>
            </label>
            <label class="field" style="width:150px"><span>Mechanism</span>
              <select v-model="sandboxMechanism">
                <option value="SUPPRESSION">Suppression</option>
                <option value="ROUNDING">Rounding</option>
                <option value="CONSISTENT_RANDOM">Consistent random</option>
              </select>
            </label>
            <label class="field" style="width:110px"><span>Budget cap</span>
              <input type="number" step="0.1" v-model.number="sandboxCap" />
            </label>
            <button class="primary" :disabled="busy" @click="runSandbox">Evaluate policies</button>
          </div>

          <div v-if="sandboxReport" class="grid cols-2">
            <div v-for="o in sandboxReport.outcomes" :key="o.policy" class="policy-card"
                 :style="o.answerable ? 'border-color: rgba(63,185,80,0.35)' : 'border-color: rgba(248,81,73,0.35)'">
              <div class="row">
                <strong>{{ o.label }}</strong>
                <span class="badge" :class="o.answerable ? 'ok' : 'bad'" style="margin-left:auto">
                  {{ o.answerable ? 'answers' : 'withheld' }}</span>
              </div>
              <div class="small" style="margin-top:6px">{{ o.decision }}</div>
              <div class="small faint" style="margin-top:6px">
                {{ o.publishedCells }} published · {{ o.suppressedCells }} suppressed · Δε {{ o.projectedLoss }} · Σε {{ o.resultingCumulativeLoss }}
              </div>
              <div v-if="o.answerableQuestions.length" class="small" style="margin-top:6px">
                <span class="faint">answerable:</span> {{ o.answerableQuestions.slice(0, 4).join(' · ') }}<span v-if="o.answerableQuestions.length > 4"> …</span>
              </div>
              <div v-if="o.blockedQuestions.length" class="small faint" style="margin-top:4px">
                {{ o.blockedQuestions.length }} cell(s) suppressed
              </div>
              <div v-if="o.inferredIntervals.length" class="mono-sm faint" style="margin-top:6px; max-height:90px; overflow:auto">
                <div v-for="(iv, i) in o.inferredIntervals.slice(0, 6)" :key="i">{{ iv }}</div>
              </div>
            </div>
          </div>
          <div v-else class="empty">Choose a request and evaluate the four policy levers.</div>
        </div>
      </section>

      <!-- Publish gate -->
      <section class="panel">
        <div class="panel-head"><h2>Publish through the gate</h2><div class="spacer"></div>
          <span class="small faint">every request is written to the ledger, published or not</span></div>
        <div class="panel-body">
          <div class="row wrap" style="gap:12px; margin-bottom:12px">
            <label class="field" style="width:200px"><span>Surface</span>
              <select v-model="pubSurface">
                <option v-for="s in surfaces" :key="s.code" :value="s.code">{{ s.code }}</option>
              </select>
            </label>
            <div class="field"><span>Dimensions</span>
              <div class="row" style="gap:6px">
                <button v-for="d in dimOptions" :key="d" class="sm"
                        :class="pubDims.includes(d) ? 'primary' : ''" @click="toggle(pubDims, d)">{{ d }}</button>
              </div>
            </div>
            <label class="field" style="width:140px"><span>Centres</span>
              <select v-model="pubGran"><option value="COARSE">Coarse</option><option value="FINE">Fine</option></select>
            </label>
            <label class="field" style="width:150px"><span>Mechanism</span>
              <select v-model="pubMechanism">
                <option value="SUPPRESSION">Suppression</option>
                <option value="ROUNDING">Rounding</option>
                <option value="CONSISTENT_RANDOM">Consistent random</option>
              </select>
            </label>
            <label class="field" style="width:110px"><span>Budget cap</span>
              <input type="number" step="0.1" v-model.number="pubCap" />
            </label>
            <button :disabled="busy" @click="previewPublish">Preview</button>
            <button class="primary" :disabled="busy" @click="doPublish">Publish</button>
          </div>

          <div v-if="publishPreview">
            <div class="row" style="margin-bottom:8px">
              <span class="badge" :class="publishPreview.blocked ? 'bad' : 'ok'">{{ publishPreview.verdict }}</span>
              <span class="small">{{ publishPreview.rationale }}</span>
            </div>
            <div v-if="publishPreview.findings?.length" class="stack" style="margin-bottom:8px">
              <div v-for="(f, i) in publishPreview.findings" :key="i" class="notice bad small">{{ f }}</div>
            </div>
            <div class="small faint mono-sm">
              {{ publishPreview.publishedCells }} published · {{ publishPreview.suppressedCells }} suppressed ·
              budget {{ publishPreview.budgetKind }} Δε {{ publishPreview.projectedLoss }} ·
              overlap {{ Math.round((publishPreview.overlapFraction || 0) * 100) }}% ·
              sim: {{ publishPreview.simulationVerdict }}
            </div>
          </div>
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.rel-edge { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
.pair-row { border: 1px solid var(--line); border-radius: 8px; padding: 10px 12px; }
.policy-card { border: 1px solid var(--line); border-radius: 8px; padding: 12px; background: var(--panel-2); }
.bad { color: var(--bad); }
</style>
