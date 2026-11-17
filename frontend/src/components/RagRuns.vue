<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRagStore } from '../stores/rag'
import { chunkerLabel, retrieverLabel } from '../ragModes'
import { formatNumber } from '../format'
import type { RagMiss, RagRun } from '../ragTypes'
import Card from './Card.vue'

const store = useRagStore()
const { runs } = storeToRefs(store)

interface Line {
  key: string
  run: RagRun
  retriever: string
  hit1: number
  hit3: number
  hit5: number
  mrr: number
  avgMs: number
  p95Ms: number
  misses: RagMiss[]
}

/** 조각 크기와 질문 수가 같은 실행끼리만 비교한다(겹침은 고정 길이에만 있어 조건 칸에 적는다). */
function groupKey(r: RagRun): string {
  return `${r.size}|${r.questions}`
}

/**
 * 같은 조건끼리 묶고, 묶음 안에서 MRR 이 높은 순서로 순위를 매긴다(같으면 hit@1, 그다음 평균 시간). 묶음은 최근 실행 순.
 * 같은 조건(청킹, 크기, 겹침, 검색 방식)을 여러 번 실행했으면 가장 최근 것만 순위에 올린다. 같은 조건이 두 줄 나란히 서서
 * 최고와 최저를 나눠 갖는 일을 막으려는 것이다(runs 는 최근 실행이 앞).
 */
const groups = computed(() => {
  const map = new Map<string, Line[]>()
  const seen = new Set<string>()
  for (const r of runs.value) {
    const k = groupKey(r)
    if (!map.has(k)) map.set(k, [])
    for (const row of r.rows) {
      const cond = `${r.chunker}|${r.size}|${r.overlap}|${row.retriever}`
      if (seen.has(cond)) continue
      seen.add(cond)
      map.get(k)!.push({ key: `${r.id}-${row.retriever}`, run: r, ...row })
    }
  }
  // 같은 조건의 더 최근 실행에 모든 줄이 밀려난 묶음은 줄이 없다. 그런 묶음은 건너뛴다(rows[0] 을 읽다가 표 전체가 깨지지 않게).
  return [...map.entries()].filter(([, lines]) => lines.length > 0).map(([k, lines]) => {
    const rows = [...lines].sort((a, b) => b.mrr - a.mrr || b.hit1 - a.hit1 || a.avgMs - b.avgMs)
    const r = rows[0].run
    return { key: k, title: `조각 크기 ${formatNumber(r.size)}자 · 질문 ${r.questions}개`, rows }
  })
})

const open = ref('')
const pct = (v: number) => `${v.toFixed(1)}%`
const ms = (v: number) => `${v.toFixed(2)}ms`

function conditions(r: RagRun): string {
  const list = [`조각 ${formatNumber(r.chunks)}개(평균 ${formatNumber(r.avgChars)}자)`]
  if (r.unreachable) list.push(`정답이 잘려 어디에도 없는 질문 ${r.unreachable}개`)
  if (r.truncated) list.push(`입력 한도(512토큰)를 넘어 뒤가 잘린 조각 ${r.truncated}개(벡터는 앞부분만 반영)`)
  if (r.saved === false) list.push('서버에 저장되지 않음(새로고침하면 사라짐)')
  list.push(r.finishedAt.slice(5, 16).replace('T', ' '))
  return list.join(' · ')
}

/** 고정 길이는 겹침 값이 결과를 바꾸므로 이름 옆에 적는다. */
const chunkerName = (r: RagRun) => chunkerLabel(r.chunker) + (r.chunker === 'fixed' ? ` (겹침 ${r.overlap})` : '')

/** 질문 하나가 차지하는 비율(%). 질문 수는 결과에 들어 있다. */
const onePct = computed(() => (runs.value.length ? (100 / runs.value[0].questions).toFixed(1) : '3'))

function clearAll() {
  if (confirm('저장된 평가 결과를 모두 삭제합니다. 계속할까요?')) store.clearRuns()
}
</script>

<template>
  <Card id="card-results" title="평가 결과">
    <template #actions>
      <button v-if="runs.length" class="ghost" @click="clearAll">기록 지우기</button>
    </template>

    <p v-if="!runs.length" class="muted">아직 평가 기록이 없습니다. 위에서 평가를 실행해 보세요.</p>

    <div v-else class="scroll">
      <table>
        <thead>
          <tr>
            <th class="num">순위</th>
            <th>청킹</th>
            <th>검색</th>
            <th>MRR</th>
            <th>hit@1</th>
            <th>hit@3</th>
            <th>hit@5</th>
            <th>평균 시간</th>
            <th>p95</th>
            <th>놓친 질문</th>
            <th>조건</th>
          </tr>
        </thead>
        <tbody v-for="g in groups" :key="g.key + g.rows[0].key">
          <tr class="group-row">
            <td colspan="11">같은 조건끼리 순위를 매깁니다 — {{ g.title }}</td>
          </tr>
          <template v-for="(l, i) in g.rows" :key="l.key">
            <tr :class="{ best: g.rows.length > 1 && i === 0, worst: g.rows.length > 1 && i === g.rows.length - 1 }">
              <td class="num rank">{{ i + 1 }}</td>
              <td>{{ chunkerName(l.run) }}</td>
              <td>
                {{ retrieverLabel(l.retriever) }}
                <span v-if="g.rows.length > 1 && i === 0" class="tag best-tag">최고</span>
                <span v-if="g.rows.length > 1 && i === g.rows.length - 1" class="tag worst-tag">최저</span>
              </td>
              <td class="strong">{{ l.mrr.toFixed(3) }}</td>
              <td>{{ pct(l.hit1) }}</td>
              <td>{{ pct(l.hit3) }}</td>
              <td>{{ pct(l.hit5) }}</td>
              <td>{{ ms(l.avgMs) }}</td>
              <td>{{ ms(l.p95Ms) }}</td>
              <td>
                <button v-if="l.misses.length" class="ghost" :aria-expanded="open === l.key" @click="open = open === l.key ? '' : l.key">
                  {{ l.misses.length }}개 {{ open === l.key ? '접기' : '보기' }}
                </button>
                <span v-else class="muted">없음</span>
              </td>
              <td class="cond wrap">{{ conditions(l.run) }}</td>
            </tr>
            <tr v-if="open === l.key">
              <td colspan="11" class="wrap">
                <ul class="legend miss-list">
                  <li v-for="m in l.misses" :key="m.question"><b>질문</b> {{ m.question }} <span class="muted">→ 1위로 나온 곳: {{ m.top1 }}</span></li>
                </ul>
              </td>
            </tr>
          </template>
        </tbody>
      </table>
      <table class="kv">
        <tbody>
          <tr><th scope="row">MRR</th><td>정답이 몇 번째에 나왔는지의 역수 평균(1등 1, 2등 0.5, 5등 안에 없으면 0). 1에 가까울수록 좋고, 이 표의 순위 기준입니다</td></tr>
          <tr><th scope="row">hit@k</th><td>정답 조각이 상위 k개 안에 있는 질문의 비율</td></tr>
          <tr><th scope="row">놓친 질문</th><td>상위 5개 안에 정답이 없던 질문. 눌러서 어떤 질문을 놓쳤고 대신 어디가 1위로 나왔는지 봅니다</td></tr>
          <tr><th scope="row">정답이 잘림</th><td>조각 경계에서 정답 문구가 둘로 쪼개져 어떤 조각에도 통째로 없는 질문. 이 경우는 검색 방식과 상관없이 못 찾습니다</td></tr>
          <tr><th scope="row">뒤가 잘린 조각</th><td>조각이 임베딩 모델의 입력 한도(512 토큰)보다 길어서 뒤쪽이 벡터에 반영되지 않은 조각 수. 긴 조각을 쓴 결과에서 벡터 검색 점수가 낮으면 이것 때문일 수 있습니다</td></tr>
          <tr><th scope="row">주의</th><td>질문이 적어서 질문 하나(약 {{ onePct }}%)의 차이는 의미를 두지 않습니다. 같은 조건을 여러 번 실행했으면 가장 최근 결과만 표에 올립니다</td></tr>
          <tr><th scope="row">저장</th><td>결과는 서버(test_result 테이블)에 저장되어 새로고침해도 유지됩니다</td></tr>
        </tbody>
      </table>
    </div>
  </Card>
</template>

<style scoped>
.miss-list { margin: 0; }
</style>
