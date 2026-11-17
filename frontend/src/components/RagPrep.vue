<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useRagStore } from '../stores/rag'
import { useFreshLog } from '../composables/useFreshLog'
import { formatMb, formatNumber } from '../format'
import Card from './Card.vue'

const store = useRagStore()
const { status, statusError, preparing, running, searching } = storeToRefs(store)
const freshLog = useFreshLog(preparing, ['model', 'index'])

// 상태 조회는 RagView 가 한다(마운트 때와 4초마다). 여기서는 따로 부르지 않는다.
</script>

<template>
  <Card id="card-prep" title="모델·문서 준비">
    <table class="kv">
      <tbody>
        <tr><th scope="row">검색 대상</th><td>{{ status?.docsDir || 'docs 폴더' }} 의 마크다운 문서(*.md). 이 저장소의 실험 노트를 질문으로 찾습니다</td></tr>
        <tr><th scope="row">임베딩 모델</th><td>multilingual-e5-small(양자화). 서비스 안에서 직접 돌려서 API 키나 별도 설치가 필요 없고, 처음 한 번만 약 135MB를 내려받습니다</td></tr>
        <tr v-if="status">
          <th scope="row">상태</th>
          <td>
            모델 <b>{{ status.modelReady ? '준비됨' : status.modelBytes ? '내려받음, 아직 메모리에 안 올림' : '아직 안 받음' }}</b>
            <template v-if="status.modelBytes"> ({{ formatMb(status.modelBytes) }})</template>
            · 문서 {{ status.docs.length }}개 · 평가 질문 {{ status.questions }}개
          </td>
        </tr>
        <tr v-for="d in status?.docs ?? []" :key="d.name" class="muted"><th scope="row">{{ d.name }}</th><td>{{ formatNumber(d.chars) }}자</td></tr>
        <tr v-if="status?.unmatched.length" class="warn-text">
          <th scope="row">질문 확인</th>
          <td>정답 문구를 문서에서 못 찾은 질문이 {{ status.unmatched.length }}개 있습니다(문서가 바뀐 것 같습니다): {{ status.unmatched.join(' / ') }}</td>
        </tr>
        <tr v-if="status?.error" class="warn-text"><th scope="row">문서 오류</th><td>{{ status.error }}</td></tr>
        <tr v-if="statusError" class="warn-text">
          <th scope="row">상태 조회 실패</th>
          <td>{{ statusError }} (서버가 꺼져 있거나 연결이 끊겼을 수 있습니다. 4초마다 다시 시도합니다)</td>
        </tr>
        <tr class="muted"><th scope="row">참고</th><td>모델 저장 위치: {{ status?.modelDir }}. 문서를 고친 뒤에는 다시 눌러야 검색에 반영됩니다</td></tr>
      </tbody>
    </table>

    <div v-if="preparing" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">모델과 문서를 준비하는 중</p>
      <p class="muted">{{ freshLog?.message ?? '처음에는 모델을 내려받느라 1분 정도 걸립니다' }}</p>
    </div>

    <template #footer>
      <div class="foot-grid">
        <button class="primary" :class="{ busy: preparing }" :disabled="preparing || running || searching || !!status?.running" @click="store.prepare()">
          <span v-if="preparing" class="btn-spinner" aria-hidden="true" />
          {{ preparing ? '준비 중…' : status?.modelReady ? '문서 다시 읽기' : '모델 받고 준비하기' }}
        </button>
      </div>
    </template>
  </Card>
</template>

