<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useJusoStore } from '../stores/juso'
import Card from './Card.vue'
import { formatDuration, formatNumber } from '../format'

const store = useJusoStore()
const { tables, tablesLoading, running } = storeToRefs(store)

function confirmTruncate(engine: string) {
  if (confirm(`${engine} 테이블의 모든 데이터를 삭제합니다. 계속할까요?`)) store.truncate(engine)
}
</script>

<template>
  <Card id="card-tables" title="테이블 상태">
    <template #actions>
      <button class="ghost" :class="{ busy: tablesLoading }" :disabled="running || tablesLoading" :aria-busy="tablesLoading" @click="store.refreshTables()">
        <span v-if="tablesLoading" class="btn-spinner" aria-hidden="true" />
        {{ tablesLoading ? '불러오는 중…' : '새로고침' }}
      </button>
    </template>

    <div class="tables-wrap">
    <div class="tables">
      <div v-for="t in tables" :key="t.table" class="table-card">
        <div class="name">{{ t.table }} <span class="tag">{{ t.engine }}</span></div>
        <div class="count">{{ formatNumber(t.count) }}<small> 건</small></div>
        <dl>
          <div><dt>COUNT(*) 시간</dt><dd>{{ formatDuration(t.countMs) }}</dd></div>
          <div><dt>데이터</dt><dd>{{ t.dataMb.toFixed(1) }} MB</dd></div>
          <div><dt>인덱스</dt><dd>{{ t.indexMb.toFixed(1) }} MB</dd></div>
        </dl>
        <button class="danger" :disabled="running" @click="confirmTruncate(t.engineKey)">비우기</button>
      </div>
    </div>
    <div v-if="tablesLoading" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">테이블 정보 불러오는 중</p>
      <p class="muted">행 수(COUNT)를 세는 중이라 시간이 걸릴 수 있습니다</p>
    </div>
    </div>
  </Card>
</template>
