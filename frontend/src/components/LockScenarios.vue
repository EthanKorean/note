<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useLockStore } from '../stores/lock'
import { useFreshLog } from '../composables/useFreshLog'
import { allIds, experimentOf, scenarios } from '../lockScenarios'
import type { LockScenario } from '../lockScenarios'
import type { LockRun } from '../lockTypes'
import Card from './Card.vue'
import LockTable from './LockTable.vue'

const store = useLockStore()
const { running, runOwner, blocked, serverBusy, status, queue, latest } = storeToRefs(store)

/** 어느 시나리오 버튼을 눌렀는지('all' 은 전체 실행). 그 버튼에만 "실행 중"을 보여 준다. */
const active = ref('')
/** 이 카드에서 시작한 실행인지(직접 실행 카드의 실행에는 스피너를 띄우지 않는다). */
const myRun = computed(() => running.value && runOwner.value === 'scenarios')
const freshLog = useFreshLog(myRun, ['lock'])

/** 시나리오마다 한 번만 계산해 둔다. */
const tables = computed(() => {
  const out = new Map<string, { items: { id: string; run?: LockRun }[]; done: boolean; any: boolean }>()
  for (const s of scenarios) {
    const items = s.show.map((id) => ({ id, run: latest.value.get(id) }))
    out.set(s.id, { items, done: s.run.every((id) => latest.value.has(id)), any: items.some((i) => i.run) })
  }
  return out
})

function runScenario(s: LockScenario) {
  active.value = s.id
  store.runMany(s.run.map((id) => ({ id })), 'scenarios')
}

function runAll() {
  active.value = 'all'
  store.runMany(allIds().map((id) => ({ id })), 'scenarios')
}

const queueLabel = computed(() => experimentOf(queue.value.current)?.label ?? '')
</script>

<template>
  <Card id="card-scenario" title="시나리오로 따라 하기">
    <table class="kv">
      <tbody>
        <tr><th scope="row">쓰는 법</th><td>위에서부터 순서대로 "실행" 버튼을 누릅니다. 실행할 때마다 상품과 주문이 처음 상태로 초기화되고, 끝나면 DB 에 실제로 남은 값으로 재고가 맞는지 확인합니다</td></tr>
        <tr><th scope="row">흐름</th><td>락 없이 만든 코드의 문제 → 락으로 막는 3가지 → 여러 상품을 잠글 때의 데드락 → 막는 방법 → 트랜잭션 길이</td></tr>
        <tr><th scope="row">내 측정</th><td>각 시나리오에 내 PC 에서 쟀을 때의 값을 적어 뒀습니다. 직접 돌린 결과와 비교하는 용도이고, 시간과 재시도 횟수는 돌릴 때마다 달라집니다</td></tr>
        <tr class="muted"><th scope="row">참고</th><td>주문자는 스레드로 흉내 냅니다(최대 50개가 동시에 출발). 락 대기는 5초가 넘으면 오류로 끝나도록 해 뒀습니다</td></tr>
      </tbody>
    </table>

    <div v-for="s in scenarios" :id="s.id" :key="s.id" class="scn">
      <div class="scn-head">
        <h3>{{ s.no }}. {{ s.title }}</h3>
        <span class="tag" :class="tables.get(s.id)!.done ? 'st-done' : 'st-todo'">{{ tables.get(s.id)!.done ? '결과 있음' : '아직 안 함' }}</span>
        <button class="primary scn-btn" :class="{ busy: myRun && active === s.id }" :disabled="blocked" @click="runScenario(s)">
          <span v-if="myRun && active === s.id" class="btn-spinner" aria-hidden="true" />
          {{ myRun && active === s.id ? '실행 중…' : tables.get(s.id)!.done ? '다시 실행' : '실행' }}
        </button>
      </div>
      <table class="kv">
        <tbody>
          <tr><th scope="row">왜</th><td>{{ s.why }}</td></tr>
          <tr><th scope="row">조건</th><td>{{ s.condition }}</td></tr>
          <tr><th scope="row">볼 것</th><td>{{ s.look }}</td></tr>
          <tr class="muted"><th scope="row">내 측정</th><td>{{ s.reference }}</td></tr>
        </tbody>
      </table>
      <LockTable v-if="tables.get(s.id)!.any" :items="tables.get(s.id)!.items" compact rank />
    </div>

    <div v-if="myRun" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">
        <template v-if="queue.total > 1">{{ queue.index }}/{{ queue.total }} · </template>{{ queueLabel }} 실행 중
      </p>
      <p class="muted">{{ freshLog?.message ?? '주문을 동시에 보내는 중입니다' }}</p>
    </div>

    <template #footer>
      <div class="foot-run scn-foot">
        <span v-if="serverBusy" class="muted">서버에서 다른 실험이 실행 중입니다({{ status?.current }})</span>
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
.scn-foot { align-items: center; gap: 12px; }
</style>
