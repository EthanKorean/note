<script setup lang="ts">
import { computed, reactive, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRagStore } from '../stores/rag'
import { useFreshLog } from '../composables/useFreshLog'
import { chunkerLabel, chunkers, retrieverLabel, retrievers } from '../ragModes'
import Card from './Card.vue'

const store = useRagStore()
const { searching, serverBusy, blocked, status, searchResult, searchError } = storeToRefs(store)
const freshLog = useFreshLog(searching, ['index'])

const p = reactive({ q: '', retriever: 'hybrid', chunker: 'heading', size: 500, overlap: 50 })
const notReady = computed(() => !status.value?.modelReady)

function run() {
  if (blocked.value) {
    // 예시 질문을 눌렀는데 조용히 아무 일도 안 일어나면 멈춘 것처럼 보인다. 이유를 알린다.
    searchError.value = notReady.value ? '모델이 아직 준비되지 않았습니다.' : '다른 작업이 실행 중이라 지금은 검색할 수 없습니다.'
    return
  }
  if (!p.q.trim()) {
    searchError.value = '질문을 입력하세요.'
    return
  }
  const needed = p.chunker === 'fixed' ? [p.size, p.overlap] : [p.size]
  if (!needed.every((v) => typeof v === 'number' && Number.isFinite(v))) {
    searchError.value = '비어 있거나 숫자가 아닌 입력칸이 있습니다.'
    return
  }
  store.search({ ...p, q: p.q.trim(), overlap: p.chunker === 'fixed' ? p.overlap : 0, topK: 5 })
}

// 시나리오 카드의 예시 질문을 받아 입력칸에 넣고 바로 검색한다.
watch(
  () => store.draftQuestion,
  (q) => {
    if (!q) return
    p.q = q
    store.draftQuestion = ''
    run()
  },
)

/** 한글 입력에서 Enter 는 글자 조합을 끝내는 키이기도 하다. 조합 중에는 검색하지 않는다. */
function onEnter(e: KeyboardEvent) {
  if (e.isComposing || e.keyCode === 229) return // 229: Safari 는 조합을 끝내는 Enter 에서 isComposing 이 false 로 온다
  run()
}
</script>

<template>
  <Card id="card-try" title="질문해 보기">
    <table class="kv">
      <tbody>
        <tr><th scope="row">하는 일</th><td>질문을 넣으면 문서에서 가장 관련 있는 조각 5개를 찾아 보여 줍니다. RAG에서 "찾기" 단계만 직접 확인하는 화면입니다</td></tr>
        <tr><th scope="row">예시</th><td>"Redis 메모리가 모자랄 때 lfu와 lru 중 어느 쪽이 적중률이 높았나?" / "LOAD DATA는 병렬 방식보다 빨랐나?"</td></tr>
        <tr class="muted"><th scope="row">참고</th><td>답변 만들기(찾은 조각을 Claude에게 주고 답을 쓰게 하기)는 API 키가 있어야 해서 아직 없습니다</td></tr>
      </tbody>
    </table>

    <p v-if="searchResult" class="muted result-line">
      {{ retrieverLabel(searchResult.retriever) }} · {{ chunkerLabel(searchResult.chunker) }} · 조각 {{ searchResult.chunks }}개 중 {{ searchResult.hits.length }}개 · 검색
      {{ searchResult.ms.toFixed(2) }}ms
    </p>
    <ol v-if="searchResult" class="hits">
      <li v-for="h in searchResult.hits" :key="h.rank" class="hit">
        <div class="hit-head">
          <b>{{ h.rank }}위</b>
          <span class="muted">점수 {{ h.score }}</span>
          <span class="hit-src">{{ h.source }}<template v-if="h.heading"> &gt; {{ h.heading }}</template></span>
        </div>
        <pre class="hit-text">{{ h.text }}</pre>
      </li>
      <li v-if="!searchResult.hits.length" class="muted">관련 있는 조각을 찾지 못했습니다(키워드 검색은 질문의 단어가 하나도 없으면 결과가 없습니다)</li>
    </ol>

    <div v-if="searching" class="loading" role="status" aria-live="polite">
      <div class="spinner" aria-hidden="true" />
      <p class="loading-title">검색 중</p>
      <p class="muted">{{ freshLog?.message ?? '처음 쓰는 청킹 조건이면 조각을 임베딩하느라 몇 초 걸립니다' }}</p>
    </div>

    <template #footer>
      <div class="foot-grid">
        <label class="foot-field wide"><span>질문</span>
          <input v-model="p.q" type="text" placeholder="문서에 대해 궁금한 것을 적어 보세요" :disabled="searching" @keydown.enter="onEnter" /></label>
      </div>
      <div class="foot-grid">
        <label class="foot-field"><span>검색 방식</span>
          <select v-model="p.retriever" :disabled="searching">
            <option v-for="m in retrievers" :key="m.value" :value="m.value">{{ m.label }}</option>
          </select></label>
        <label class="foot-field"><span>청킹</span>
          <select v-model="p.chunker" :disabled="searching">
            <option v-for="m in chunkers" :key="m.value" :value="m.value">{{ m.label }}</option>
          </select></label>
        <label class="foot-field"><span>조각 크기 (글자)</span>
          <input v-model.number="p.size" type="number" min="100" max="3000" :disabled="searching" /></label>
        <label class="foot-field"><span>겹침 (글자)</span>
          <input v-model.number="p.overlap" type="number" min="0" :disabled="searching || p.chunker !== 'fixed'" /></label>
        <button class="primary" :class="{ busy: searching }" :disabled="blocked" @click="run">
          <span v-if="searching" class="btn-spinner" aria-hidden="true" />
          {{ searching ? '검색 중…' : '검색' }}
        </button>
      </div>
      <p v-if="notReady" class="warn-text">모델이 아직 준비되지 않았습니다. 위 "모델·문서 준비"를 먼저 실행하세요.</p>
      <p v-else-if="serverBusy" class="muted">서버에서 다른 작업이 실행 중입니다({{ status?.current }}). 끝나면 검색할 수 있습니다.</p>
      <p v-if="searchError" class="warn-text" role="alert">{{ searchError }}</p>
    </template>
  </Card>
</template>

<style scoped>
.result-line { margin: 12px 0 6px; font-size: 1rem; }
.hits { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
.hit { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; }
.hit-head { display: flex; gap: 10px; align-items: baseline; flex-wrap: wrap; font-size: 1rem; }
.hit-src { color: var(--accent); font-weight: 600; }
.hit-text { margin: 8px 0 0; max-height: 180px; overflow: auto; white-space: pre-wrap; word-break: break-word; font-family: inherit; font-size: 1rem; background: var(--tag-bg); border-radius: 6px; padding: 8px 10px; }
.wide { grid-column: 1 / -1; }
.wide input { width: 100%; }
</style>
