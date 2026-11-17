<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRagStore } from '../stores/rag'
import { useFreshLog } from '../composables/useFreshLog'
import { allConditions, scenarios, tryQuestions } from '../ragScenarios'
import type { Scenario } from '../ragScenarios'
import { chunkerLabel, retrieverLabel, retrievers } from '../ragModes'
import { formatNumber } from '../format'
import type { RagEvalParams, RagRow, RagRun } from '../ragTypes'
import Card from './Card.vue'

const store = useRagStore()
const { runs, running, runOwner, blocked, serverBusy, status, queue } = storeToRefs(store)

const retrieverKeys = retrievers.map((m) => m.value)
const notReady = computed(() => !status.value?.modelReady)

/** 어느 시나리오 버튼을 눌렀는지('all' 은 전체 실행). 그 버튼에만 "실행 중"을 보여 준다. */
const active = ref('')
/** 이 카드에서 시작한 실행인지(다른 카드의 실행에는 스피너를 띄우지 않는다). */
const myRun = computed(() => running.value && runOwner.value === 'scenarios')
const freshLog = useFreshLog(myRun, ['index', 'eval'])

/**
 * 조건에 맞는 가장 최근 결과. 이 시나리오의 표는 검색 방식 세 가지를 모두 보여 주므로, 세 가지가 다 들어 있고
 * 지금 질문 수와 같은 실행만 쓴다(직접 실행에서 일부 검색 방식만 돌렸거나, 질문 세트가 바뀐 뒤의 옛 결과는 제외).
 */
function find(c: RagEvalParams): RagRun | undefined {
  const q = status.value?.questions
  return runs.value.find(
    (r) =>
      r.chunker === c.chunker &&
      r.size === c.size &&
      r.overlap === c.overlap &&
      (q == null || r.questions === q) &&
      retrieverKeys.every((k) => r.rows.some((row) => row.retriever === k)),
  )
}

/** 시나리오마다 한 번만 계산해 둔다(렌더링 중에 반복해서 찾지 않게). */
const tables = computed(() => {
  const out = new Map<string, { rows: { c: RagEvalParams; run?: RagRun }[]; best: number; done: boolean }>()
  for (const s of scenarios) {
    const rows = s.conditions.map((c) => ({ c, run: find(c) }))
    let best = -1
    for (const r of rows) for (const x of r.run?.rows ?? []) best = Math.max(best, x.mrr)
    out.set(s.id, { rows, best, done: rows.every((r) => !!r.run) })
  }
  return out
})

const done = (s: Scenario) => tables.value.get(s.id)?.done ?? false
const anyDone = (s: Scenario) => tables.value.get(s.id)?.rows.some((r) => !!r.run) ?? false
const rowOf = (run: RagRun, k: string): RagRow | undefined => run.rows.find((x) => x.retriever === k)
const condLabel = (c: RagEvalParams) => `${chunkerLabel(c.chunker)}${c.chunker === 'fixed' ? ` 겹침 ${c.overlap}` : ''} · ${formatNumber(c.size)}자`

function runScenario(s: Scenario) {
  active.value = s.id
  store.runMany(s.conditions, 'scenarios')
}

function runAll() {
  active.value = 'all'
  store.runMany(allConditions(), 'scenarios')
}

/** 예시 질문을 "질문해 보기" 카드에 넣고 그 카드로 이동해 바로 검색한다. */
async function ask(q: string) {
  store.draftQuestion = q
  window.dispatchEvent(new CustomEvent('open-card', { detail: 'card-try' }))
  await nextTick()
  document.getElementById('card-try')?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

const queueLabel = computed(() => (queue.value.current ? chunkerLabel(queue.value.current) : ''))
</script>

<template>
  <Card id="card-scenario" title="시나리오로 따라 하기">
    <table class="kv">
      <tbody>
        <tr><th scope="row">쓰는 법</th><td>위에서부터 순서대로 "실행" 버튼을 누릅니다. 조건은 시나리오마다 정해져 있고, 끝나면 결과와 보는 법이 아래에 나옵니다</td></tr>
        <tr><th scope="row">흐름</th><td>앞 결과를 보고 "다음에 무엇을 바꿔 볼까"로 이어집니다: 자르는 방식 → 찾는 방식 → 조각 크기 → 겹침 → 입력 한도</td></tr>
        <tr><th scope="row">내 측정</th><td>각 시나리오에 내 PC에서 쟀을 때의 값을 적어 뒀습니다. 직접 돌린 결과와 비교하는 용도이고 정답은 아닙니다</td></tr>
        <tr class="muted"><th scope="row">참고</th><td>조건을 직접 바꿔 보려면 아래 "직접 조건 바꿔 실행"을 쓰세요. 모델이 준비돼 있어야 실행할 수 있습니다</td></tr>
      </tbody>
    </table>

    <div v-for="s in scenarios" :id="s.id" :key="s.id" class="scn">
      <div class="scn-head">
        <h3>{{ s.no }}. {{ s.title }}</h3>
        <span class="tag" :class="done(s) ? 'st-done' : 'st-todo'">{{ done(s) ? '결과 있음' : '아직 안 함' }}</span>
        <button class="primary scn-btn" :class="{ busy: myRun && active === s.id }" :disabled="blocked" @click="runScenario(s)">
          <span v-if="myRun && active === s.id" class="btn-spinner" aria-hidden="true" />
          {{ myRun && active === s.id ? '실행 중…' : done(s) ? '다시 실행' : '실행' }}
        </button>
      </div>
      <table class="kv">
        <tbody>
          <tr><th scope="row">왜</th><td>{{ s.why }}</td></tr>
          <tr><th scope="row">조건</th><td>{{ s.conditions.map(condLabel).join(' / ') }} — 검색 방식 세 가지 모두</td></tr>
          <tr><th scope="row">볼 것</th><td>{{ s.look }}</td></tr>
          <tr class="muted"><th scope="row">내 측정</th><td>{{ s.reference }}</td></tr>
        </tbody>
      </table>

      <div v-if="anyDone(s)" class="scroll">
        <table class="scn-table">
          <thead>
            <tr>
              <th>청킹 조건</th>
              <th>조각</th>
              <th v-for="k in retrieverKeys" :key="k">{{ retrieverLabel(k) }} MRR <small class="muted">(hit@5)</small></th>
              <th>뒤가 잘린 조각</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in tables.get(s.id)!.rows" :key="condLabel(row.c)">
              <td>{{ condLabel(row.c) }}</td>
              <template v-if="row.run">
                <td>{{ formatNumber(row.run.chunks) }}개 · 평균 {{ formatNumber(row.run.avgChars) }}자</td>
                <td v-for="k in retrieverKeys" :key="k" :class="{ strong: rowOf(row.run, k)?.mrr === tables.get(s.id)!.best }">
                  {{ rowOf(row.run, k)?.mrr.toFixed(3) }} <small class="muted">({{ rowOf(row.run, k)?.hit5.toFixed(1) }}%)</small>
                  <span v-if="rowOf(row.run, k)?.mrr === tables.get(s.id)!.best" class="tag best-tag">최고</span>
                </td>
                <td>{{ row.run.truncated }}개</td>
              </template>
              <td v-else :colspan="retrieverKeys.length + 2" class="muted">아직 안 돌림</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <div id="scn-try" class="scn">
      <div class="scn-head">
        <h3>6. 직접 물어 보기</h3>
        <span class="tag st-todo">눈으로 확인</span>
      </div>
      <table class="kv">
        <tbody>
          <tr><th scope="row">왜</th><td>점수로는 안 보이는 것을 직접 본다. 질문 종류에 따라 검색 방식의 1위가 어떻게 달라지는지, 문서에 없는 질문에 검색이 어떻게 반응하는지 확인한다</td></tr>
          <tr><th scope="row">쓰는 법</th><td>예시 질문을 누르면 "질문해 보기" 카드로 이동해서 바로 검색합니다. 거기서 검색 방식을 바꿔 가며 1위를 비교해 보세요</td></tr>
        </tbody>
      </table>
      <ul class="try-list">
        <li v-for="t in tryQuestions" :key="t.q">
          <button class="ghost" :disabled="blocked" @click="ask(t.q)">{{ t.q }}</button>
          <span class="muted">{{ t.look }}</span>
        </li>
      </ul>
    </div>

    <div v-if="myRun" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">
        <template v-if="queue.total > 1">{{ queue.index }}/{{ queue.total }} · </template>{{ queueLabel }} 평가 중
      </p>
      <p class="muted">{{ freshLog?.message ?? '조각을 임베딩하고 질문을 검색하는 중입니다' }}</p>
    </div>

    <template #footer>
      <div class="foot-run scn-foot">
        <span v-if="notReady" class="warn-text">모델이 아직 준비되지 않았습니다. 위 "모델·문서 준비"를 먼저 실행하세요.</span>
        <span v-else-if="serverBusy" class="muted">서버에서 다른 작업이 실행 중입니다({{ status?.current }})</span>
        <button class="primary" :class="{ busy: myRun && active === 'all' }" :disabled="blocked" @click="runAll">
          <span v-if="myRun && active === 'all'" class="btn-spinner" aria-hidden="true" />
          {{ myRun && active === 'all' ? '실행 중…' : '1~5번 순서대로 모두 실행' }}
        </button>
      </div>
    </template>
  </Card>
</template>

<style scoped>
.scn { margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--border); }
.scn:first-of-type { margin-top: 14px; }
.scn-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.scn-head h3 { margin: 0; font-size: 1rem; flex: 1 1 auto; }
.scn .kv { margin-top: 10px; }
.scn-btn { min-width: 96px; }
.scn-table { margin-top: 10px; }
.scn-foot { align-items: center; gap: 12px; }
.st-done { background: var(--accent); color: var(--accent-text); }
.st-todo { background: transparent; color: var(--muted); border: 1px dashed var(--border); }
.try-list { list-style: none; margin: 10px 0 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.try-list li { display: flex; flex-direction: column; gap: 4px; align-items: flex-start; font-size: 1rem; }
.try-list button { text-align: left; }
</style>
