<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useJusoStore } from '../stores/juso'
import LoadForm from '../components/LoadForm.vue'
import RunHistory from '../components/RunHistory.vue'
import TableStatus from '../components/TableStatus.vue'
import ResultSummary from '../components/ResultSummary.vue'
import MethodNotes from '../components/MethodNotes.vue'
import PageNav from '../components/PageNav.vue'

const store = useJusoStore()
const { error } = storeToRefs(store)

const sections = [
  { id: 'card-load', label: '적재 실행' },
  { id: 'card-runs', label: '실행 결과' },
  { id: 'card-summary', label: '결과 정리' },
  { id: 'card-tables', label: '테이블 상태' },
  { id: 'card-notes', label: '방식별 장단점' },
]

onMounted(() => {
  store.loadRuns()
  store.refreshTables()
})
</script>

<template>
  <div class="with-toc">
    <div class="page">
      <header>
        <h1>대용량 적재</h1>
        <p class="muted">도로명주소 2026-10 고시분(642만 건)을 방식별로 적재하고 시간과 속도를 비교합니다.</p>
      </header>

      <div v-if="error" class="error" role="alert">{{ error }}</div>

      <LoadForm />
      <RunHistory />
      <ResultSummary />
      <TableStatus />
      <MethodNotes />
    </div>
    <PageNav title="대용량 적재" :items="sections" />
  </div>
</template>
