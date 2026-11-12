<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useImageStore } from '../stores/image'
import { useWsStore } from '../stores/ws'
import { imageModeLabel, imageModes } from '../imageModes'
import type { ImageBenchParams } from '../types'
import Card from './Card.vue'

const store = useImageStore()
const { running, seeding, serverBusy, queue, status, error } = storeToRefs(store)
const { lastLog } = storeToRefs(useWsStore())

const selected = ref<string[]>(['prebuilt', 'redis'])
const focus = ref('redis')
const hotPercent = ref(80) // 화면에서는 %로 입력한다
const p = reactive({
  requests: 20000,
  threads: 16,
  products: 1000,
  hotSetSize: 50,
  rotateEvery: 5000,
  originLatencyMs: 5,
  ttlSec: 60,
  hotThreshold: 2,
  windowSec: 60,
  stampedeGuard: true,
  maxMemoryMb: 128,
  evictionPolicy: 'allkeys-lfu',
  resetCache: true,
})

// 상품 수를 줄이면 인기 상품 수도 같이 줄인다(인기 상품 수는 전체 상품 수를 넘을 수 없다).
watch(
  () => p.products,
  (n) => {
    if (n >= 1 && p.hotSetSize > n) p.hotSetSize = n
  },
)

const sample = computed(() => imageModes.find((m) => m.value === focus.value) ?? imageModes[0])
const usesRedis = computed(() => selected.value.includes('redis'))
const noImages = computed(() => !status.value || status.value.count < p.products)

function run() {
  // 입력칸을 비우면 빈 값("")이 서버로 가므로 보내기 전에 숫자인지 확인한다.
  const nums = [p.requests, p.threads, p.products, p.hotSetSize, p.rotateEvery, p.originLatencyMs, p.ttlSec, p.hotThreshold, p.windowSec, p.maxMemoryMb, hotPercent.value]
  if (nums.some((v) => typeof v !== 'number' || !Number.isFinite(v))) {
    error.value = '비어 있거나 숫자가 아닌 입력칸이 있습니다.'
    return
  }
  if (p.hotSetSize > p.products) p.hotSetSize = p.products
  const order = imageModes.map((m) => m.value).filter((v) => selected.value.includes(v))
  store.runMany(
    order.map((mode) => ({ ...p, mode, hotRatio: hotPercent.value / 100 }) as ImageBenchParams),
  )
}
</script>

<template>
  <Card id="card-bench" title="캐시 실험 실행">
    <div class="method-pick">
      <fieldset class="methods" :disabled="running">
        <legend>방식 (체크한 방식을 순서대로 실행)</legend>
        <div v-for="m in imageModes" :key="m.value" class="radio" :class="{ on: m.value === focus }">
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
          <li>{{ `요청의 ${hotPercent}%가 인기 상품 ${p.hotSetSize}개에 몰리고, ${p.rotateEvery ? p.rotateEvery.toLocaleString() + '건마다 인기 상품이 통째로 바뀝니다.' : '인기 상품은 바뀌지 않습니다.'}` }}</li>
          <li>크기는 웹 40% · 앱 40% · 썸네일 20%로 섞어서 요청합니다.</li>
          <li>원본 저장소 지연 {{ p.originLatencyMs }}ms는 원격 스토리지(S3 등)를 흉내 낸 값입니다. 로컬 디스크는 너무 빨라서 캐시 효과가 안 보입니다.</li>
          <li v-if="noImages" class="warn-text">이미지가 {{ p.products.toLocaleString() }}개보다 적습니다. 위 "이미지 준비"에서 먼저 만드세요.</li>
        </ul>
      </div>
    </div>

    <div v-if="running" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">
        <template v-if="queue.total > 1">{{ queue.index }}/{{ queue.total }} · </template>{{ imageModeLabel(queue.current) }} 실행 중
      </p>
      <p class="muted">{{ lastLog?.message ?? `요청 ${p.requests.toLocaleString()}건을 보내는 중입니다` }}</p>
    </div>

    <template #footer>
      <h3 class="foot-h">부하</h3>
      <div class="foot-grid">
        <label class="foot-field"><span>요청 수</span>
          <input v-model.number="p.requests" type="number" min="100" max="2000000" :disabled="running" /><small>100 ~ 200만</small></label>
        <label class="foot-field"><span>동시 스레드</span>
          <input v-model.number="p.threads" type="number" min="1" max="64" :disabled="running" /><small>1 ~ 64</small></label>
        <label class="foot-field"><span>전체 상품 수</span>
          <input v-model.number="p.products" type="number" min="1" max="100000" :disabled="running" /><small>만들어 둔 수 이하</small></label>
        <label class="foot-field"><span>원본 저장소 지연 (ms)</span>
          <input v-model.number="p.originLatencyMs" type="number" min="0" max="500" :disabled="running" /><small>원격 스토리지 흉내</small></label>
      </div>

      <h3 class="foot-h">인기 패턴</h3>
      <div class="foot-grid">
        <label class="foot-field"><span>인기 상품 수</span>
          <input v-model.number="p.hotSetSize" type="number" min="1" :max="p.products" :disabled="running" /><small>지금 잘 팔리는 상품</small></label>
        <label class="foot-field"><span>인기 상품에 몰리는 요청 (%)</span>
          <input v-model.number="hotPercent" type="number" min="0" max="100" :disabled="running" /><small>나머지는 전체에서 고르게</small></label>
        <label class="foot-field"><span>인기 집합 교체 주기 (요청 수)</span>
          <input v-model.number="p.rotateEvery" type="number" min="0" :disabled="running" /><small>0이면 안 바뀜</small></label>
      </div>

      <h3 class="foot-h">Redis 캐시 <small class="muted">{{ usesRedis ? '' : '(Redis 방식을 선택하면 적용)' }}</small></h3>
      <div class="foot-grid">
        <label class="foot-field"><span>유효 시간 TTL (초)</span>
          <input v-model.number="p.ttlSec" type="number" min="1" :disabled="running || !usesRedis" /><small>지나면 캐시에서 빠짐</small></label>
        <label class="foot-field"><span>캐시에 올리는 기준 (횟수)</span>
          <input v-model.number="p.hotThreshold" type="number" min="1" :disabled="running || !usesRedis" /><small>윈도 안에 이만큼 찾았을 때</small></label>
        <label class="foot-field"><span>"자주" 세는 윈도 (초)</span>
          <input v-model.number="p.windowSec" type="number" min="1" :disabled="running || !usesRedis" /><small>이 시간 안의 횟수만 센다</small></label>
        <label class="foot-field"><span>Redis 메모리 한도 (MB)</span>
          <input v-model.number="p.maxMemoryMb" type="number" min="1" :disabled="running || !usesRedis" /><small>가득 차면 제거 정책 적용</small></label>
        <label class="foot-field"><span>제거 정책</span>
          <select v-model="p.evictionPolicy" :disabled="running || !usesRedis">
            <option value="allkeys-lfu">allkeys-lfu</option>
            <option value="allkeys-lru">allkeys-lru</option>
            <option value="volatile-ttl">volatile-ttl</option>
            <option value="noeviction">noeviction</option>
          </select><small>lfu 적게 쓴 것 / lru 오래 안 쓴 것</small></label>
        <div class="foot-checks">
          <label><input v-model="p.stampedeGuard" type="checkbox" :disabled="running || !usesRedis" /> 동시 요청 합치기 (폭주 방지)</label>
          <label><input v-model="p.resetCache" type="checkbox" :disabled="running" /> 실험 전에 캐시 비우기</label>
        </div>
      </div>

      <div class="foot-run">
        <button class="primary" :class="{ busy: running }" :disabled="running || seeding || serverBusy || !selected.length || noImages" :aria-busy="running" @click="run">
          <span v-if="running" class="btn-spinner" aria-hidden="true" />
          {{ running ? '실행 중…' : serverBusy ? '서버가 작업 중…' : selected.length > 1 ? `${selected.length}개 방식 실행` : '실험 시작' }}
        </button>
      </div>
    </template>
  </Card>
</template>
