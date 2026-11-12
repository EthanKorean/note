<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useImageStore } from '../stores/image'
import { useWsStore } from '../stores/ws'
import { formatNumber } from '../format'
import type { ReplaceRun, ReplaceStrategy } from '../types'
import Card from './Card.vue'

const store = useImageStore()
const ws = useWsStore()
const { replaceRuns, replacing, running, seeding, serverBusy, status } = storeToRefs(store)
const { lastLog } = storeToRefs(ws)

interface Strategy {
  value: ReplaceStrategy
  label: string
  summary: string
}

const strategies: Strategy[] = [
  {
    value: 'none',
    label: '무효화 없음 (TTL이 지나길 기다림)',
    summary: '이미지를 바꿔도 캐시는 그대로 둡니다. 가장 단순하지만 TTL이 끝날 때까지 옛 이미지가 계속 나갑니다. 문제가 얼마나 큰지 보는 기준선입니다.',
  },
  {
    value: 'purge',
    label: '캐시 삭제 (바뀐 상품의 키를 지움)',
    summary: '이미지를 바꾸는 쪽이 해당 상품의 Redis 키를 직접 지웁니다. 바로 반영되지만 지우는 쪽이 Redis까지 호출해야 하고, 지우는 틈에 옛 이미지를 읽은 요청이 다시 캐시에 올릴 수 있습니다.',
  },
  {
    value: 'version',
    label: '버전 키 (키에 버전을 넣고 올림)',
    summary: '키를 img:{상품}:v{버전}:{크기}로 만들고, 이미지가 바뀌면 버전만 올립니다. 지우지 않고 새 키를 읽게 하므로 즉시 반영되고 되돌릴 일이 없지만, 옛 키가 TTL로 사라질 때까지 메모리에 남습니다.',
  },
]

const selected = ref<ReplaceStrategy[]>(['none', 'purge', 'version'])
const focus = ref<ReplaceStrategy>('purge')
const p = reactive({ items: 100, ttlSec: 300 })
const sample = computed(() => strategies.find((x) => x.value === focus.value) ?? strategies[0])
const noImages = computed(() => !status.value || status.value.count < p.items)
const label = (v: string) => strategies.find((x) => x.value === v)?.label.split(' (')[0] ?? v

function run() {
  if (typeof p.items !== 'number' || typeof p.ttlSec !== 'number' || !Number.isFinite(p.items) || !Number.isFinite(p.ttlSec)) {
    store.error = '비어 있거나 숫자가 아닌 입력칸이 있습니다.'
    return
  }
  const order = strategies.map((x) => x.value).filter((v) => selected.value.includes(v))
  store.runReplaceMany(order, p.items, p.ttlSec)
}

/** 같은 조건(상품 수, TTL)끼리만 비교한다. 옛 이미지가 적은 순서. */
const groups = computed(() => {
  const map = new Map<string, ReplaceRun[]>()
  for (const r of replaceRuns.value) {
    const k = `${r.items}|${r.ttlSec}`
    if (!map.has(k)) map.set(k, [])
    map.get(k)!.push(r)
  }
  return [...map.values()].map((list) => ({
    title: `상품 ${formatNumber(list[0].items)}개 · TTL ${list[0].ttlSec}초`,
    rows: [...list].sort((a, b) => a.stalePct - b.stalePct || a.invalidateMs - b.invalidateMs),
  }))
})

const pct = (v: number) => `${(v * 100).toFixed(0)}%`
const ms = (v: number) => `${v.toFixed(2)}ms`

function clearAll() {
  if (confirm('교체 실험 결과를 모두 삭제합니다. 계속할까요?')) store.clearReplaceRuns()
}
</script>

<template>
  <Card id="card-replace" title="이미지 교체와 캐시 무효화">
    <div class="method-pick">
      <fieldset class="methods" :disabled="replacing">
        <legend>무효화 방식 (체크한 방식을 순서대로 실행)</legend>
        <div v-for="m in strategies" :key="m.value" class="radio" :class="{ on: m.value === focus }">
          <input v-model="selected" type="checkbox" :value="m.value" :aria-label="m.label + ' 실행에 포함'" />
          <button type="button" class="pick-name" :aria-pressed="m.value === focus" @click="focus = m.value">
            {{ m.label }}
            <small class="code-name">{{ m.value }}</small>
          </button>
        </div>
      </fieldset>
      <div class="sample">
        <p class="sample-sum">{{ sample.summary }}</p>
        <ul class="legend">
          <li>상품 {{ p.items }}개의 이미지(웹·앱·썸네일 3종)를 캐시에 올린 뒤 모두 새 그림으로 바꾸고, 다시 요청해서 <b>옛 이미지가 나간 횟수</b>를 셉니다.</li>
          <li>바뀐 이미지는 오른쪽 아래에 <b>v1, v2…</b> 표시가 붙어서 이미지 목록에서도 구분됩니다.</li>
          <li class="muted">실험하면 이미지 파일이 실제로 바뀝니다. 되돌리려면 "이미지 준비"에서 다시 만드세요.</li>
          <li v-if="noImages" class="warn-text">이미지가 {{ p.items }}개보다 적습니다. 위 "이미지 준비"에서 먼저 만드세요.</li>
        </ul>
      </div>
    </div>

    <div v-if="replacing" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">교체 실험 중</p>
      <p class="muted">{{ lastLog?.message ?? '요청 중…' }}</p>
    </div>

    <template #footer>
      <div class="foot-grid">
        <label class="foot-field"><span>교체할 상품 수</span>
          <input v-model.number="p.items" type="number" min="1" max="1000" :disabled="replacing" /><small>1 ~ 1,000</small></label>
        <label class="foot-field"><span>TTL (초)</span>
          <input v-model.number="p.ttlSec" type="number" min="1" max="86400" :disabled="replacing" /><small>캐시 유효 시간</small></label>
      </div>
      <div class="foot-run">
        <button class="primary" :class="{ busy: replacing }" :disabled="replacing || running || seeding || serverBusy || !selected.length || noImages" :aria-busy="replacing" @click="run">
          <span v-if="replacing" class="btn-spinner" aria-hidden="true" />
          {{ replacing ? '실행 중…' : selected.length > 1 ? `${selected.length}개 방식 실행` : '실험 시작' }}
        </button>
      </div>
    </template>
  </Card>

  <Card id="card-replace-results" title="교체 실험 결과">
    <template #actions>
      <button v-if="replaceRuns.length" class="ghost" @click="clearAll">기록 지우기</button>
    </template>
    <p v-if="!replaceRuns.length" class="muted">아직 실행 기록이 없습니다. 위에서 실험을 실행해 보세요.</p>
    <div v-else class="scroll">
      <table>
        <thead>
          <tr>
            <th class="num">순위</th>
            <th>방식</th>
            <th>옛 이미지가 나간 횟수</th>
            <th>무효화 시간</th>
            <th>지운 키</th>
            <th>남은 옛 키</th>
          </tr>
        </thead>
        <tbody v-for="g in groups" :key="g.title + g.rows[0].id">
          <tr class="group-row">
            <td colspan="6">같은 조건끼리 순위를 매깁니다 — {{ g.title }}</td>
          </tr>
          <tr v-for="(r, i) in g.rows" :key="r.id" :class="{ best: g.rows.length > 1 && i === 0, worst: g.rows.length > 1 && i === g.rows.length - 1 }">
            <td class="num rank">{{ i + 1 }}</td>
            <td>
              {{ label(r.strategy) }}
              <span v-if="g.rows.length > 1 && i === 0" class="tag best-tag">최선</span>
              <span v-if="g.rows.length > 1 && i === g.rows.length - 1" class="tag worst-tag">최악</span>
            </td>
            <td class="strong">{{ formatNumber(r.stale) }} / {{ formatNumber(r.requests) }}건 ({{ pct(r.stalePct) }})</td>
            <td>{{ ms(r.invalidateMs) }}</td>
            <td>{{ formatNumber(r.keysDeleted) }}</td>
            <td>{{ formatNumber(r.orphanKeys) }}</td>
          </tr>
        </tbody>
      </table>
      <ul class="muted legend">
        <li><b>옛 이미지가 나간 횟수</b> 교체한 뒤 다시 요청했을 때 새 파일과 다른 이미지가 나간 횟수. 순위는 이 비율이 낮은 순서(같으면 무효화 시간이 짧은 순서)</li>
        <li><b>무효화 시간</b> 상품 전체의 무효화 작업에만 쓴 시간. 파일을 바꾸는 시간은 빠집니다</li>
        <li><b>지운 키</b> 삭제 방식이 Redis에서 직접 지운 키 수. 버전 방식은 지우지 않으므로 0입니다</li>
        <li><b>남은 옛 키</b> 실험이 끝난 뒤에도 Redis에 남아 있는 쓸모없는 옛 키. TTL이 지나야 사라지고, 그동안 메모리를 차지합니다</li>
        <li>결과는 서버(test_result 테이블)에 저장되어 새로고침해도 유지됩니다</li>
      </ul>
    </div>
  </Card>
</template>
