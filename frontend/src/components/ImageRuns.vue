<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useImageStore } from '../stores/image'
import { imageModeLabel } from '../imageModes'
import { formatNumber } from '../format'
import type { ImageRun } from '../types'
import Card from './Card.vue'
import Sparkline from './Sparkline.vue'

const store = useImageStore()
const { runs } = storeToRefs(store)

/** 부하·인기 패턴이 같은 실행끼리만 비교할 수 있다(방식별 Redis 설정은 제외). */
function groupKey(r: ImageRun): string {
  const p = r.params
  return [p.requests, p.threads, p.products, p.hotSetSize, p.hotRatio, p.rotateEvery, p.originLatencyMs].join('|')
}

function groupTitle(r: ImageRun): string {
  const p = r.params
  const rot = p.rotateEvery ? `${formatNumber(p.rotateEvery)}건마다 교체` : '교체 없음'
  return `${formatNumber(p.requests)}건 · ${p.threads}스레드 · 인기 ${p.hotSetSize}개(${Math.round(p.hotRatio * 100)}%) · ${rot} · 원본 ${p.originLatencyMs}ms`
}

/** 같은 조건끼리 묶고, 묶음 안에서 평균 응답이 짧은 순서로 순위를 매긴다(묶음은 최근 실행 순). */
const groups = computed(() => {
  const map = new Map<string, ImageRun[]>()
  for (const r of runs.value) {
    const k = groupKey(r)
    if (!map.has(k)) map.set(k, [])
    map.get(k)!.push(r)
  }
  return [...map.values()].map((list) => {
    const rows = [...list].sort((a, b) => a.avgMs - b.avgMs)
    return { title: groupTitle(rows[0]), rows }
  })
})

const ms = (v: number) => `${v.toFixed(2)}ms`

function conditions(r: ImageRun): string {
  const p = r.params
  const list = [`${formatNumber(p.requests)}건`, `${p.threads}스레드`, `인기 ${p.hotSetSize}개(${Math.round(p.hotRatio * 100)}%)`]
  list.push(p.rotateEvery ? `${formatNumber(p.rotateEvery)}건마다 교체` : '교체 없음')
  list.push(`원본 ${p.originLatencyMs}ms`)
  if (p.mode === 'redis') {
    list.push(`TTL ${p.ttlSec}초`, `기준 ${p.hotThreshold}회/${p.windowSec}초`, `${p.evictionPolicy.replace('allkeys-', '')} ${p.maxMemoryMb}MB`)
    if (p.stampedeGuard) list.push('폭주방지')
  }
  return list.join(' · ')
}

function signed(d: number, fmt: (abs: number) => string): string {
  if (d === 0) return '±0'
  return `${d > 0 ? '+' : '−'}${fmt(Math.abs(d))}`
}

/** "(이전 차이 / 누적 차이)" 꼴. 이전 = 같은 묶음의 바로 위 순위, 누적 = 1위. 1위는 빈 문자열. */
function diff(rows: ImageRun[], i: number, pick: (r: ImageRun) => number, fmt: (abs: number) => string): string {
  if (i === 0) return ''
  const cur = pick(rows[i])
  return `(${signed(cur - pick(rows[i - 1]), fmt)} / 누적 ${signed(cur - pick(rows[0]), fmt)})`
}

function clearAll() {
  if (confirm('저장된 실험 결과를 모두 삭제합니다. 계속할까요?')) store.clearRuns()
}
</script>

<template>
  <Card id="card-results" title="실행 결과">
    <template #actions>
      <button v-if="runs.length" class="ghost" @click="clearAll">기록 지우기</button>
    </template>

    <p v-if="!runs.length" class="muted">아직 실행 기록이 없습니다. 위에서 실험을 실행해 보세요.</p>

    <div v-else class="scroll">
      <table>
        <thead>
          <tr>
            <th class="num">순위</th>
            <th>방식</th>
            <th>평균 응답</th>
            <th>p95</th>
            <th>처리량 (건/초)</th>
            <th>적중률</th>
            <th>원본 읽기</th>
            <th>적중률 추이</th>
            <th>조건</th>
          </tr>
        </thead>
        <tbody v-for="g in groups" :key="g.title + g.rows[0].id">
          <tr class="group-row">
            <td colspan="9">같은 조건끼리 순위를 매깁니다 — {{ g.title }}</td>
          </tr>
          <tr v-for="(r, i) in g.rows" :key="r.id" :class="{ best: g.rows.length > 1 && i === 0, worst: g.rows.length > 1 && i === g.rows.length - 1 }">
            <td class="num rank">{{ i + 1 }}</td>
            <td>
              {{ imageModeLabel(r.params.mode) }}
              <span v-if="g.rows.length > 1 && i === 0" class="tag best-tag">최단</span>
              <span v-if="g.rows.length > 1 && i === g.rows.length - 1" class="tag worst-tag">최장</span>
            </td>
            <td class="strong">
              {{ ms(r.avgMs) }}
              <small class="delta">{{ diff(g.rows, i, (x) => x.avgMs, ms) }}</small>
            </td>
            <td>
              {{ ms(r.p95Ms) }}
              <small class="delta">{{ diff(g.rows, i, (x) => x.p95Ms, ms) }}</small>
            </td>
            <td class="strong">
              {{ formatNumber(r.throughput) }}
              <small class="delta">{{ diff(g.rows, i, (x) => x.throughput, (d) => formatNumber(d)) }}</small>
            </td>
            <td>{{ r.params.mode === 'redis' ? `${(r.hitRatio * 100).toFixed(1)}%` : '-' }}</td>
            <td>{{ formatNumber(r.originReads) }}</td>
            <td>
              <Sparkline v-if="r.params.mode === 'redis'" :values="r.timelineHit" label="적중률 추이" />
              <span v-else class="muted">-</span>
            </td>
            <td class="cond wrap">{{ conditions(r) }}</td>
          </tr>
        </tbody>
      </table>
      <ul class="muted legend">
        <li><b>평균 응답</b> 요청 한 건이 이미지를 받기까지 걸린 평균 시간. 짧은 순서로 순위를 매기고, 괄호는 (바로 위 순위와의 차이 / 누적 = 1위와의 차이). 시간은 +가 더 느리고, 처리량은 −가 더 느림</li>
        <li><b>p95</b> 가장 느린 5%를 뺀 나머지 중 가장 느린 값. 평균에 안 보이는 "가끔 느린 요청"을 본다</li>
        <li><b>원본 읽기</b> 디스크(원격 스토리지)를 실제로 읽은 횟수. 캐시가 막아 준 만큼 줄어든다</li>
        <li><b>적중률 추이</b> 요청 순서를 20구간으로 나눈 구간별 적중률. 인기 상품이 바뀌는 지점마다 떨어졌다가 회복된다(Redis 방식만)</li>
        <li>결과는 서버(test_result 테이블)에 저장되어 새로고침해도 유지됩니다</li>
      </ul>
    </div>
  </Card>
</template>
