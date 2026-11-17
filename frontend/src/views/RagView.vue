<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useRagStore } from '../stores/rag'
import { useWsStore } from '../stores/ws'
import Card from '../components/Card.vue'
import RagPrep from '../components/RagPrep.vue'
import RagScenarios from '../components/RagScenarios.vue'
import RagEval from '../components/RagEval.vue'
import RagRuns from '../components/RagRuns.vue'
import RagTry from '../components/RagTry.vue'
import PageNav from '../components/PageNav.vue'

const store = useRagStore()
const { error } = storeToRefs(store)

const sections = [
  { id: 'card-why', label: '왜 필요한가' },
  { id: 'card-prep', label: '모델·문서 준비' },
  { id: 'card-scenario', label: '시나리오로 따라 하기' },
  { id: 'card-eval', label: '직접 조건 바꿔 실행' },
  { id: 'card-results', label: '평가 결과' },
  { id: 'card-try', label: '질문해 보기' },
]

onMounted(() => {
  useWsStore().connectTopic('rag') // RAG 서비스의 진행 메시지(웹소켓 패널)는 이 화면에서 연결한다
  store.loadRuns()
  store.refreshStatus()
})

// 다른 탭이나 새로고침 전에 시작한 작업도 알아채도록 서버 상태를 주기적으로 확인한다(가벼운 조회 한 건).
// 서버의 작업이 끝났을 때만 결과를 다시 불러온다. 이 화면에서 직접 시작한 작업이 끝난 경우에는 이미 결과가 목록에 있으므로 불러오지 않는다.
const timer = setInterval(() => {
  if (!document.hidden) store.refreshStatus() // 숨어 있는 탭은 쉰다
}, 4000)
onUnmounted(() => clearInterval(timer))
watch(
  () => store.status?.running,
  (now, was) => {
    if (was && !now && !store.running && !store.preparing && !store.searching) store.loadRuns()
  },
)
</script>

<template>
  <div class="with-toc">
    <div class="page">
      <header>
        <h1>RAG 검색</h1>
        <p class="muted">문서를 조각으로 잘라 벡터로 바꿔 두고, 질문과 가까운 조각을 찾는 "찾기" 단계를 청킹과 검색 방식별로 재서 비교합니다.</p>
      </header>

      <div v-if="error" class="error" role="alert">{{ error }}</div>

      <Card id="card-why" title="왜 필요한가">
        <table class="kv">
          <tbody>
            <tr><th scope="row">문제</th><td>AI 모델은 우리 문서를 모른다. 문서를 통째로 넣으면 한도, 비용, 속도가 나빠진다</td></tr>
            <tr><th scope="row">해결</th><td>질문이 올 때 관련 있는 조각만 찾아서 모델에게 붙여 준다(RAG)</td></tr>
            <tr><th scope="row">핵심</th><td>답의 품질은 "정답이 든 조각을 찾아 오느냐"에 달려 있다. 못 찾으면 모델은 모르는 채로 지어낸다</td></tr>
            <tr><th scope="row">잰다</th><td>조각을 어떻게 자르는지, 뜻으로 찾을지 단어로 찾을지에 따라 찾는 비율이 달라진다. 같은 질문 세트로 숫자를 내서 비교한다</td></tr>
            <tr><th scope="row">범위</th><td>이번에는 "찾기"까지. 찾은 조각으로 답을 쓰게 하는 단계는 API 키가 있을 때 붙인다</td></tr>
            <tr><th scope="row">용어</th><td>모르는 말은 AI 메뉴의 "AI 용어 정리"에서 찾을 수 있다</td></tr>
          </tbody>
        </table>
      </Card>

      <RagPrep />
      <RagScenarios />
      <RagEval />
      <RagRuns />
      <RagTry />
    </div>
    <PageNav title="RAG 검색" :items="sections" />
  </div>
</template>
