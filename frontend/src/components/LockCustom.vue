<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLockStore } from '../stores/lock'
import { useFreshLog } from '../composables/useFreshLog'
import { DEFAULTS, experimentOf, experiments } from '../lockScenarios'
import { lockCode } from '../lockCode'
import Card from './Card.vue'
import CodeBlock from './CodeBlock.vue'

const store = useLockStore()
const { running, runOwner, blocked, serverBusy, status } = storeToRefs(store)
/** 입력 검사 오류. 이 카드 안에 보여 준다(화면 맨 위의 오류 띠는 멀리 있어 놓치기 쉽다). */
const formError = ref('')

const myRun = computed(() => running.value && runOwner.value === 'custom')
const freshLog = useFreshLog(myRun, ['lock'])

const p = reactive<{ id: string; buyers: number; stock: number; payMs: number }>({
  id: 'stock-none',
  buyers: DEFAULTS.buyers.stock,
  stock: DEFAULTS.stock,
  payMs: DEFAULTS.payMs,
})
const group = computed(() => experimentOf(p.id)?.group ?? 'stock')
// 실험을 바꾸면 주문 수를 그 실험의 기본값으로 맞춘다(시나리오와 같은 조건에서 출발하게).
watch(() => p.id, () => (p.buyers = DEFAULTS.buyers[group.value]))

function run() {
  formError.value = ''
  const ints = [p.buyers, ...(group.value === 'stock' ? [p.stock] : []), ...(group.value === 'tx' ? [p.payMs] : [])]
  if (!ints.every((v) => Number.isInteger(v))) {
    formError.value = '비어 있거나 정수가 아닌 입력칸이 있습니다.'
    return
  }
  if (p.buyers < 2 || p.buyers > 200) {
    formError.value = '주문자 수는 2 ~ 200 이어야 합니다.'
    return
  }
  if (group.value === 'stock' && (p.stock < 1 || p.stock > 1000)) {
    formError.value = '재고는 1 ~ 1,000 이어야 합니다.'
    return
  }
  if (group.value === 'tx' && (p.payMs < 0 || p.payMs > 100)) {
    formError.value = '결제 시간은 0 ~ 100ms 이어야 합니다.'
    return
  }
  store.runMany(
    [{ id: p.id, buyers: p.buyers, ...(group.value === 'stock' ? { stock: p.stock } : {}), ...(group.value === 'tx' ? { payMs: p.payMs } : {}) }],
    'custom',
  )
}
</script>

<template>
  <Card id="card-custom" title="직접 조건 바꿔 실행">
    <table class="kv">
      <tbody>
        <tr><th scope="row">쓰는 법</th><td>실험 하나를 고르고 주문 수 같은 조건을 바꿔서 실행합니다. 시나리오와 같은 곳에 결과가 쌓입니다</td></tr>
        <tr class="warn-text"><th scope="row">주의</th><td>"결제를 트랜잭션 안에서"는 주문 50건 이상 + 결제 100ms 이면 줄 끝의 주문이 락을 5초(대기 한도)에 가깝게 기다려 실패할 수 있습니다. 타이밍에 따라 안 날 수도 있고, 나면 실험이 고장 난 것이 아니라 그 자체가 관찰 대상입니다</td></tr>
        <tr><th scope="row">해 볼 것</th><td>재고를 늘리고 주문을 줄이면 락 없음도 문제가 없어 보일 수 있습니다(동시에 부딪히지 않아서). 주문을 200건으로 늘리면 낙관적 락의 재시도 초과 실패가 늘어나는지 봅니다</td></tr>
      </tbody>
    </table>
    <div class="method-pick">
      <fieldset class="methods" :disabled="running">
        <legend>실험</legend>
        <label v-for="e in experiments" :key="e.id" class="radio" :class="{ on: e.id === p.id }">
          <input v-model="p.id" type="radio" name="lock-exp" :value="e.id" />
          {{ e.label }} <small class="muted">{{ e.how }}</small>
        </label>
      </fieldset>
      <div class="sample">
        <CodeBlock v-for="b in lockCode[p.id] ?? []" :key="b.title" :block="b" />
      </div>
    </div>

    <div v-if="formError" class="error" role="alert">{{ formError }}</div>

    <div v-if="myRun" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">{{ experimentOf(p.id)?.label }} 실행 중</p>
      <p class="muted">{{ freshLog?.message ?? '주문을 동시에 보내는 중입니다' }}</p>
    </div>

    <template #footer>
      <div class="foot-grid">
        <label class="foot-field"><span>주문자 수</span>
          <input v-model.number="p.buyers" type="number" min="2" max="200" :disabled="running" /><small>2 ~ 200 (동시에 최대 50)</small></label>
        <label class="foot-field"><span>재고</span>
          <input v-model.number="p.stock" type="number" min="1" max="1000" :disabled="running || group !== 'stock'" /><small>재고 차감 실험에서만 적용 (1 ~ 1,000)</small></label>
        <label class="foot-field"><span>결제 시간 (ms)</span>
          <input v-model.number="p.payMs" type="number" min="0" max="100" :disabled="running || group !== 'tx'" /><small>트랜잭션 길이 실험에서만 적용 (0 ~ 100)</small></label>
      </div>
      <div class="foot-run">
        <button class="primary" :class="{ busy: myRun }" :disabled="blocked" :aria-busy="myRun" @click="run">
          <span v-if="myRun" class="btn-spinner" aria-hidden="true" />
          {{ myRun ? '실행 중…' : '실행' }}
        </button>
        <span v-if="serverBusy" class="muted">서버에서 다른 실험이 실행 중입니다({{ status?.current }})</span>
      </div>
    </template>
  </Card>
</template>
