<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useJusoStore } from '../stores/juso'
import { useWsStore } from '../stores/ws'
import { methodSamples } from '../methodSamples'
import type { LoadParams } from '../types'
import Card from './Card.vue'
import CodeBlock from './CodeBlock.vue'

const methods = methodSamples
/** 한 문장에 여러 행을 넣는 방식은 바인드 변수 제한으로 이 값을 넘길 수 없다(서버 MultiRowLoader). */
const MULTI_ROW_MAX = 2849
const isMultiRow = (m: string) => m === 'parallel' || m === 'mybatis-parallel-foreach'

const store = useJusoStore()
const ws = useWsStore()
const { running: clientRunning, queue } = storeToRefs(store)
const { lastLog, serverBusy } = storeToRefs(ws)
// 이 화면에서 시작했거나, 서버에서 다른 곳(다른 탭, 스크립트)이 실행 중이면 바쁨
const running = computed(() => clientRunning.value || serverBusy.value)

const focus = ref('loaddata-local')            // 오른쪽 예제로 보여 줄 방식
const selected = ref<string[]>(['loaddata-local']) // 실제로 실행할 방식(여러 개 가능)
const params = reactive<LoadParams>({
  engine: 'innodb',
  batchSize: 1000,
  threads: 4,
  deferIndex: false,
  relaxChecks: true,
  truncate: true,
})

const sample = computed(() => methods.find((m) => m.value === focus.value) ?? methods[0])
const allSelected = computed(() => selected.value.length === methods.length)
const multi = computed(() => selected.value.length > 1)
const onlyLoadData = computed(() => selected.value.length > 0 && selected.value.every((m) => m.startsWith('loaddata')))
const hasParallel = computed(() => selected.value.some((m) => m.includes('parallel')))
const usesRelax = computed(() => selected.value.some((m) => !m.startsWith('jpa') && !m.startsWith('mybatis')))

/** 선택한 방식 중 멀티 로우 방식이 있고 입력값이 상한을 넘으면 그 방식만 줄여 실행한다. */
const clamped = computed(() => params.batchSize > MULTI_ROW_MAX && selected.value.some(isMultiRow))

function toggleAll(e: Event) {
  selected.value = (e.target as HTMLInputElement).checked ? methods.map((m) => m.value) : []
}

function run() {
  // 방식마다 쓰지 않는 값은 기본값으로 보내 서버 검증에 걸리지 않게 한다. 목록 순서대로 하나씩 실행한다.
  const order = methods.map((m) => m.value).filter((v) => selected.value.includes(v))
  store.runLoadMany(
    order,
    (m) => {
      const p = { ...params }
      if (m.startsWith('loaddata')) p.batchSize = 1000
      if (isMultiRow(m)) p.batchSize = Math.min(p.batchSize, MULTI_ROW_MAX)
      if (!m.includes('parallel')) p.threads = 4
      if (multi.value) p.truncate = true // 여러 방식을 연달아 돌리면 매번 비워야 건수가 맞는다
      return p
    },
  )
}
</script>

<template>
  <Card id="card-load" title="적재 실행">
    <div class="method-pick">
      <fieldset class="methods" :disabled="running">
        <legend>적재 방식 (체크한 방식을 순서대로 실행)</legend>
        <label class="radio all">
          <input type="checkbox" :checked="allSelected" @change="toggleAll" />
          <span>전체 실행</span>
          <small class="muted">{{ selected.length }}/{{ methods.length }}</small>
        </label>
        <div v-for="m in methods" :key="m.value" class="radio" :class="{ on: m.value === focus }">
          <input v-model="selected" type="checkbox" :value="m.value" :aria-label="m.label + ' 실행에 포함'" />
          <button type="button" class="pick-name" :aria-pressed="m.value === focus" @click="focus = m.value">
            {{ m.label }}
            <small class="code-name">{{ m.value }}</small>
          </button>
        </div>
      </fieldset>
      <div class="sample">
        <p class="sample-sum">{{ sample.summary }}</p>
        <CodeBlock v-for="b in sample.blocks" :key="b.title" :block="b" />
      </div>
    </div>

    <div v-if="running" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">
        <template v-if="queue.total > 1">{{ queue.index }}/{{ queue.total }} · </template>{{ queue.current || lastLog?.method }} 적재 중
      </p>
      <p class="muted">{{ lastLog?.message ?? '요청 중…' }}</p>
    </div>

    <template #footer>
      <div class="foot-grid">
        <label class="foot-field">
          <span>배치 크기 (행)</span>
          <input v-model.number="params.batchSize" type="number" min="1" max="50000" :disabled="running || onlyLoadData" />
          <small :class="{ warn: clamped }">
            {{ clamped ? `병렬·foreach는 ${MULTI_ROW_MAX.toLocaleString()}행으로 줄여 실행` : '병렬·foreach: 2,849 이하' }}
          </small>
        </label>
        <label class="foot-field">
          <span>스레드 (병렬 방식)</span>
          <input v-model.number="params.threads" type="number" min="1" max="8" :disabled="running || !hasParallel" />
          <small>병렬 방식에서만 사용</small>
        </label>
        <div class="foot-checks">
          <label>
            <input v-model="params.relaxChecks" type="checkbox" :disabled="running || !usesRelax" /> unique/FK 검사 끄기
            <small v-if="!usesRelax" class="muted">(이 방식은 적용되지 않음)</small>
          </label>
          <label><input :checked="params.truncate || multi" type="checkbox" :disabled="running || multi" @change="params.truncate = ($event.target as HTMLInputElement).checked" /> 적재 전 테이블 비우기
            <small v-if="multi" class="muted">(여러 방식은 항상 비움)</small></label>
        </div>
        <button class="primary" :class="{ busy: running }" :disabled="running || !selected.length" :aria-busy="running" @click="run">
          <span v-if="running" class="btn-spinner" aria-hidden="true" />
          {{ running ? '적재 중…' : multi ? `${selected.length}개 방식 실행` : '적재 시작' }}
        </button>
      </div>
    </template>
  </Card>
</template>
