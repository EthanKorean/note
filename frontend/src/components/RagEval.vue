<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRagStore } from '../stores/rag'
import { useFreshLog } from '../composables/useFreshLog'
import { chunkerLabel, chunkers, retrievers } from '../ragModes'
import Card from './Card.vue'

const store = useRagStore()
const { running, runOwner, blocked, serverBusy, queue, status, error } = storeToRefs(store)

/** 이 카드에서 시작한 실행인지. 시나리오 카드에서 시작한 실행에는 이 카드에 스피너를 띄우지 않는다. */
const myRun = computed(() => running.value && runOwner.value === 'eval')
const freshLog = useFreshLog(myRun, ['index', 'eval'])

const selected = ref<string[]>(['fixed', 'paragraph', 'heading'])
const focus = ref('heading')
const picked = ref<string[]>(['vector', 'bm25', 'hybrid'])
const p = reactive({ size: 500, overlap: 50 })

const sample = computed(() => chunkers.find((m) => m.value === focus.value) ?? chunkers[0])
const notReady = computed(() => !status.value?.modelReady)
const usesOverlap = computed(() => selected.value.includes('fixed'))

function run() {
  // 겹침은 고정 길이에서만 쓰므로, 입력칸이 잠겨 있을 때는(고정 길이를 체크하지 않음) 값을 검사하지 않는다.
  const needed = usesOverlap.value ? [p.size, p.overlap] : [p.size]
  if (!needed.every((v) => typeof v === 'number' && Number.isFinite(v))) {
    error.value = '비어 있거나 숫자가 아닌 입력칸이 있습니다.'
    return
  }
  // 입력칸의 min/max 는 v-model.number 로 막히지 않는다. 같은 오류가 청킹마다 세 번 뜨지 않게 보내기 전에 한 번만 검사한다.
  if (p.size < 100 || p.size > 3000) {
    error.value = '조각 크기는 100 ~ 3,000 글자여야 합니다.'
    return
  }
  if (usesOverlap.value && (p.overlap < 0 || p.overlap >= p.size / 2)) {
    error.value = '겹침은 0 이상, 조각 크기의 절반 미만이어야 합니다.'
    return
  }
  const order = chunkers.map((m) => m.value).filter((v) => selected.value.includes(v))
  const rets = retrievers.map((m) => m.value).filter((v) => picked.value.includes(v))
  store.runMany(order.map((chunker) => ({ chunker, size: p.size, overlap: chunker === 'fixed' ? p.overlap : 0, retrievers: rets })), 'eval')
}
</script>

<template>
  <Card id="card-eval" title="직접 조건 바꿔 실행">
    <div class="method-pick">
      <fieldset class="methods" :disabled="running">
        <legend>청킹 방식 (체크한 방식을 순서대로 실행)</legend>
        <div v-for="m in chunkers" :key="m.value" class="radio" :class="{ on: m.value === focus }">
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
          <li>질문 {{ status?.questions ?? 0 }}개를 하나씩 검색해서, 정답 문구가 들어 있는 조각이 상위 5개 안에 나오는지 확인합니다</li>
          <li>정답은 조각 번호가 아니라 "정답 문구"로 적어서, 조각을 자르는 방식이 달라도 같은 질문으로 비교할 수 있습니다</li>
          <li>시간은 질문 한 건을 검색하는 데 걸린 시간입니다(벡터와 하이브리드는 질문을 벡터로 바꾸는 시간 포함, 색인 만드는 시간 제외)</li>
          <li v-if="notReady" class="warn-text">모델이 아직 준비되지 않았습니다. 위 "모델·문서 준비"를 먼저 실행하세요.</li>
        </ul>
      </div>
    </div>

    <div v-if="myRun" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">
        <template v-if="queue.total > 1">{{ queue.index }}/{{ queue.total }} · </template>{{ chunkerLabel(queue.current) }} 평가 중
      </p>
      <p class="muted">{{ freshLog?.message ?? '조각을 임베딩하고 질문을 검색하는 중입니다' }}</p>
    </div>

    <template #footer>
      <h3 class="foot-h">청킹</h3>
      <div class="foot-grid">
        <label class="foot-field"><span>조각 크기 (글자)</span>
          <input v-model.number="p.size" type="number" min="100" max="3000" :disabled="running" /><small>100 ~ 3,000</small></label>
        <label class="foot-field"><span>겹침 (글자)</span>
          <input v-model.number="p.overlap" type="number" min="0" :disabled="running || !usesOverlap" /><small>고정 길이에서만 적용, 크기의 절반 미만</small></label>
      </div>

      <h3 class="foot-h">검색 방식 (체크한 방식을 모두 재서 비교)</h3>
      <div class="foot-grid">
        <div class="foot-checks">
          <label v-for="m in retrievers" :key="m.value" :title="m.summary">
            <input v-model="picked" type="checkbox" :value="m.value" :disabled="running" /> {{ m.label }}
          </label>
        </div>
      </div>

      <div class="foot-run">
        <button class="primary" :class="{ busy: myRun }" :disabled="blocked || !selected.length || !picked.length" :aria-busy="myRun" @click="run">
          <span v-if="myRun" class="btn-spinner" aria-hidden="true" />
          {{ myRun ? '실행 중…' : '평가 실행' }}
        </button>
        <span v-if="serverBusy" class="muted">서버에서 다른 작업이 실행 중입니다({{ status?.current }})</span>
      </div>
    </template>
  </Card>
</template>
