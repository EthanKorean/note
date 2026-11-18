<script setup lang="ts">
import { computed } from 'vue'
import { formatDuration, formatNumber } from '../format'
import { experimentOf } from '../lockScenarios'
import type { LockRun } from '../lockTypes'

/** run 이 없는 항목은 "아직 안 돌림"으로 보여 준다. compact: 조건 칸을 뺀다. rank: 총 시간이 가장 짧은 행에 최단, 가장 긴 행에 최장을 붙인다(같은 조건끼리 비교하는 표에서만). */
const props = defineProps<{ items: { id: string; run?: LockRun }[]; compact?: boolean; rank?: boolean }>()

const span = computed(() => {
  const t = props.items.flatMap((i) => (i.run ? [i.run.elapsedMs] : []))
  return t.length > 1 && props.rank ? { min: Math.min(...t), max: Math.max(...t) } : null
})

function verdict(r: LockRun): string {
  if (r.consistent) return '맞음'
  return r.oversold > 0 ? `어긋남 · 초과 판매 ${formatNumber(r.oversold)}건` : '어긋남'
}

/** 어긋난 경우에만 남은 재고와 "처음 - 팔린 수량"을 나란히 보여 준다. */
function mismatch(r: LockRun): string {
  return r.products
    .filter((p) => p.finalStock !== p.initial - p.sold)
    .map((p) => `상품 ${p.id}: 남은 재고 ${p.finalStock} / 계산상 ${p.initial - p.sold}`)
    .join(' · ')
}

function condition(r: LockRun): string {
  const list = [`주문 ${formatNumber(r.buyers)}건`, `동시 ${r.threads}`]
  if (r.group === 'stock') list.push(`재고 ${r.products[0]?.initial ?? '-'}`)
  if (r.group === 'tx') list.push(`결제 ${r.payMs}ms`)
  list.push(r.finishedAt.slice(5, 19).replace('T', ' '))
  if (!r.saved) list.push('서버에 저장되지 않음(새로고침하면 사라짐)')
  return list.join(' · ')
}
</script>

<template>
  <div class="scroll">
    <table class="lock-table">
      <thead>
        <tr>
          <th>방식</th>
          <th>성공</th>
          <th>품절</th>
          <th>실패</th>
          <th>데드락</th>
          <th>재시도</th>
          <th>재고 검증</th>
          <th>총 시간</th>
          <th v-if="!compact">조건</th>
        </tr>
      </thead>
      <tbody>
        <template v-for="(it, i) in items" :key="it.run?.key ?? `${it.id}-${i}`">
          <tr>
            <td>
              <b>{{ experimentOf(it.id)?.label ?? it.id }}</b>
              <div class="muted how">{{ experimentOf(it.id)?.how }}</div>
            </td>
            <template v-if="it.run">
              <td>{{ it.run.ok }}</td>
              <td>{{ it.run.soldOut }}</td>
              <td :class="{ 'warn-text': it.run.failed > 0 }">
                {{ it.run.failed }}<span v-if="it.run.timeouts" class="muted"> (대기 초과 {{ it.run.timeouts }})</span>
              </td>
              <td :class="{ 'warn-text': it.run.deadlocks > 0 }">{{ it.run.deadlocks }}</td>
              <td>{{ it.run.retries }}</td>
              <td>
                <span class="tag verdict" :class="it.run.consistent ? 'v-ok' : 'v-bad'">{{ verdict(it.run) }}</span>
                <div v-if="!it.run.consistent" class="muted how">{{ mismatch(it.run) }}</div>
              </td>
              <td class="strong">
                {{ formatDuration(it.run.elapsedMs) }}
                <span v-if="span && span.min !== span.max && it.run.elapsedMs === span.min" class="tag best-tag">최단</span>
                <span v-if="span && span.min !== span.max && it.run.elapsedMs === span.max" class="tag worst-tag">최장</span>
              </td>
              <td v-if="!compact" class="cond wrap">{{ condition(it.run) }}</td>
            </template>
            <td v-else :colspan="compact ? 7 : 8" class="muted">아직 안 돌림</td>
          </tr>
          <tr v-if="it.run?.firstError" class="err-row">
            <td :colspan="compact ? 8 : 9" class="muted wrap">실패 이유: {{ it.run.firstError }}</td>
          </tr>
        </template>
      </tbody>
    </table>
  </div>
</template>

<style scoped>
/* 클래스 lock-table, verdict, err-row 는 스타일 외에 Playwright 테스트(e2e/lock.spec.ts)가 찾는 데도 쓴다. 이름을 바꾸면 테스트도 고친다. */
.how { font-weight: 400; }
td.warn-text { color: var(--warn-text); font-weight: 600; }
.err-row td { padding-top: 0; white-space: normal; } /* 긴 실패 이유가 표를 옆으로 늘리지 않게 줄바꿈한다 */

.v-ok { background: var(--accent); color: var(--accent-text); }
.v-bad { background: transparent; color: var(--danger); border: 1px solid currentColor; }
</style>
