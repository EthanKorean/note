<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useJusoStore } from '../stores/juso'
import Card from './Card.vue'
import { formatDuration, formatMb, formatNumber } from '../format'

const store = useJusoStore()
const { runs } = storeToRefs(store)

/** 총 소요가 짧은 순서(순위 1이 가장 빠름). 같은 시간이면 최근 실행을 앞에 둔다. */
const ranked = computed(() => [...runs.value].sort((a, b) => a.totalMs - b.totalMs))

/** 총 소요 시간이 가장 짧은/긴 기록(2건 이상일 때만 비교한다). */
const bestId = computed(() => {
  if (runs.value.length < 2) return null
  return runs.value.reduce((a, b) => (b.totalMs < a.totalMs ? b : a)).id
})
const worstId = computed(() => {
  if (runs.value.length < 2) return null
  const w = runs.value.reduce((a, b) => (b.totalMs > a.totalMs ? b : a))
  return w.id === bestId.value ? null : w.id
})

/** 방식 외에 결과를 가르는 조건(배치 크기, 스레드 수)을 보여 준다. */
function conditions(r: (typeof runs.value)[number]): string[] {
  const o = r.options
  const list: string[] = []
  if (!r.method.startsWith('loaddata')) list.push(`배치 ${formatNumber(o.batchSize)}`)
  if (r.method.includes('parallel')) list.push(`${o.threads}스레드`)
  return list
}

function signed(d: number, fmt: (abs: number) => string): string {
  if (d === 0) return '±0'
  return `${d > 0 ? '+' : '−'}${fmt(Math.abs(d))}`
}

/** "(이전 차이 / 누적 차이)" 꼴. 이전 = 바로 위 순위, 누적 = 1위. 1위는 비교 대상이 없어 빈 문자열. */
function diff(i: number, pick: (r: (typeof runs.value)[number]) => number, fmt: (abs: number) => string): string {
  if (i === 0) return ''
  const cur = pick(ranked.value[i])
  const prev = signed(cur - pick(ranked.value[i - 1]), fmt)
  const first = signed(cur - pick(ranked.value[0]), fmt)
  return `(${prev} / 누적 ${first})`
}

function clearAll() {
  if (confirm('저장된 적재 결과를 모두 삭제합니다. 계속할까요?')) store.clearRuns()
}
</script>

<template>
  <Card id="card-runs" title="실행 결과">
    <template #actions>
      <button v-if="runs.length" class="ghost" @click="clearAll">기록 지우기</button>
    </template>

    <p v-if="!runs.length" class="muted">아직 실행 기록이 없습니다. 위에서 적재를 실행해 보세요.</p>

    <div v-else class="scroll">
      <table>
        <thead>
          <tr>
            <th class="num">순위</th>
            <th>방식</th>
            <th>조건</th>
            <th class="num">총 건수</th>
            <th>DB 적재</th>
            <th>총 소요</th>
            <th>속도 (건/초)</th>
            <th>MB/초</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in ranked" :key="r.id" :class="{ best: r.id === bestId, worst: r.id === worstId }">
            <td class="num rank">{{ i + 1 }}</td>
            <td>
              {{ r.method }}
              <span v-if="r.id === bestId" class="tag best-tag">최단</span>
              <span v-if="r.id === worstId" class="tag worst-tag">최장</span>
            </td>
            <td class="cond">{{ conditions(r).join(' · ') || '-' }}</td>
            <td class="num">
              {{ formatNumber(r.fileLines) }}
              <span v-if="r.options.truncate && r.verifiedCount !== r.fileLines" class="tag warn" title="DB COUNT와 불일치">
                DB {{ formatNumber(r.verifiedCount) }}
              </span>
            </td>
            <td>
              {{ formatDuration(r.loadMs) }}
              <small class="delta">{{ diff(i, (x) => x.loadMs, (d) => formatDuration(d)) }}</small>
            </td>
            <td class="strong">
              {{ formatDuration(r.totalMs) }}
              <small class="delta">{{ diff(i, (x) => x.totalMs, (d) => formatDuration(d)) }}</small>
            </td>
            <td class="strong">
              {{ formatNumber(r.rowsPerSec) }}
              <small class="delta">{{ diff(i, (x) => x.rowsPerSec, (d) => formatNumber(d)) }}</small>
            </td>
            <td>
              {{ r.mbPerSec.toFixed(1) }}
              <small class="delta">{{ diff(i, (x) => x.mbPerSec, (d) => d.toFixed(1)) }}</small>
            </td>
          </tr>
        </tbody>
      </table>
      <ul class="muted legend">
        <li><b>총 소요</b> 행 수 세기부터 검증까지 서버 처리 전체. 짧은 순서로 순위를 매겨 위에서부터 보여 주고, 괄호는 (바로 위 순위와의 차이 / 누적 = 1위와의 차이). 시간은 +가 더 느리고, 속도는 −가 더 느림. 가장 짧은 행은 최단, 가장 긴 행은 최장으로 강조</li>
        <li><b>DB 적재</b> 적재만 잰 시간 (속도 계산 기준)</li>
        <li v-if="runs[0]"><b>데이터 파일</b> {{ formatMb(runs[0].fileBytes) }}</li>
        <li>결과는 서버(test_result 테이블)에 저장되어 새로고침해도 유지됩니다</li>
      </ul>
    </div>
  </Card>
</template>
