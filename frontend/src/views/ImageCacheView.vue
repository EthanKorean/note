<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useImageStore } from '../stores/image'
import ImagePrep from '../components/ImagePrep.vue'
import ImageBench from '../components/ImageBench.vue'
import ImageRuns from '../components/ImageRuns.vue'
import ImageReplace from '../components/ImageReplace.vue'
import PageNav from '../components/PageNav.vue'

const store = useImageStore()
const { error } = storeToRefs(store)

const sections = [
  { id: 'card-prep', label: '이미지 준비' },
  { id: 'card-bench', label: '캐시 실험 실행' },
  { id: 'card-results', label: '실행 결과' },
  { id: 'card-replace', label: '이미지 교체와 캐시 무효화' },
  { id: 'card-replace-results', label: '교체 실험 결과' },
]

onMounted(() => {
  store.loadRuns()
  store.loadReplaceRuns()
})

// 다른 탭이나 새로고침 전에 시작한 작업이 서버에서 돌고 있으면 끝날 때까지 상태를 확인한다.
let timer: ReturnType<typeof setInterval> | null = null
watch(
  () => store.serverBusy,
  (busy, was) => {
    if (busy && !timer) timer = setInterval(() => store.refreshStatus(), 3000)
    if (!busy && timer) {
      clearInterval(timer)
      timer = null
      if (was) store.loadRuns()
    }
  },
)
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="with-toc">
    <div class="page">
      <header>
        <h1>이미지 캐싱</h1>
        <p class="muted">쇼핑몰처럼 한시적으로 인기가 바뀌는 상품 이미지를, 캐시 없이 / 미리 만들어 두고 / Redis로 캐싱했을 때 응답 시간과 적중률을 비교합니다.</p>
      </header>

      <div v-if="error" class="error" role="alert">{{ error }}</div>

      <ImagePrep />
      <ImageBench />
      <ImageRuns />
      <ImageReplace />
    </div>
    <PageNav title="이미지 캐싱" :items="sections" />
  </div>
</template>
