<script setup lang="ts">
import { onMounted, onUnmounted, watch } from 'vue'
import { storeToRefs } from 'pinia'
import { useLockStore } from '../stores/lock'
import { useWsStore } from '../stores/ws'
import Card from '../components/Card.vue'
import LockScenarios from '../components/LockScenarios.vue'
import LockCustom from '../components/LockCustom.vue'
import LockResults from '../components/LockResults.vue'
import PageNav from '../components/PageNav.vue'

const store = useLockStore()
const { error, statusError, status } = storeToRefs(store)

const sections = [
  { id: 'card-why', label: '왜 필요한가' },
  { id: 'card-scenario', label: '시나리오로 따라 하기' },
  { id: 'card-custom', label: '직접 조건 바꿔 실행' },
  { id: 'card-results', label: '실험 기록' },
]

onMounted(() => {
  useWsStore().connectTopic('lock') // 락 실험의 진행 메시지(웹소켓 패널)는 이 화면에서 연결한다
  store.loadRuns()
  store.refreshStatus()
})

// 다른 탭이나 새로고침 전에 시작한 실험도 알아채도록 서버 상태를 주기적으로 확인한다(가벼운 조회 한 건).
const timer = setInterval(() => {
  if (!document.hidden) store.refreshStatus() // 숨어 있는 탭은 쉰다
}, 4000)
onUnmounted(() => clearInterval(timer))
// 서버의 실험이 끝났을 때만 결과를 다시 불러온다. 이 화면에서 직접 시작한 실험은 이미 목록에 있다.
watch(
  () => status.value?.running,
  (now, was) => {
    if (was && !now && !store.running) store.loadRuns()
  },
)
</script>

<template>
  <div class="with-toc">
    <div class="page">
      <header>
        <h1>DB 락 전략</h1>
        <p class="muted">쇼핑몰 주문으로 동시에 같은 재고를 건드릴 때 생기는 문제와 락 전략, 데드락과 방지법을 직접 재현해 비교합니다.</p>
      </header>

      <div v-if="statusError || status?.error" class="error" role="alert">{{ statusError || status?.error }}</div>
      <div v-if="error" class="error" role="alert">{{ error }}</div>

      <Card id="card-why" title="왜 필요한가">
        <table class="kv">
          <tbody>
            <tr><th scope="row">문제</th><td>주문이 몰리면 같은 재고를 여러 요청이 동시에 읽고 고친다. 평소에는 멀쩡하다가 몰릴 때만 초과 판매, 재고 어긋남이 생겨서 개발할 때는 잘 안 보인다</td></tr>
            <tr><th scope="row">해결</th><td>락으로 순서를 세운다. 방법마다 비용이 다르다: 원자적 UPDATE, 비관적 락(기다림), 낙관적 락(충돌하면 다시)</td></tr>
            <tr><th scope="row">부작용</th><td>락을 쓰면 데드락이 생길 수 있다. 상품 여러 개를 한 주문에 잠그는 쇼핑몰에서는 흔한 장애다</td></tr>
            <tr><th scope="row">전략</th><td>잠그는 순서를 통일하고, 트랜잭션은 짧게(락을 잡은 채 외부 호출을 하지 않기), 그래도 나는 데드락은 다시 시도로 받는다</td></tr>
            <tr><th scope="row">방법</th><td>상품, 주문 테이블을 두고 주문 스레드를 동시에 출발시켜 결과를 DB 에 남은 값으로 검증한다. 트랜잭션은 프레임워크에 맡기지 않고 JDBC 로 직접 다뤄서 락이 잡히고 풀리는 시점이 코드에 보인다</td></tr>
            <tr class="muted"><th scope="row">범위</th><td>MariaDB(InnoDB) 기본 격리 수준(REPEATABLE READ) 기준입니다. DB 가 달라지면 락 동작과 오류 번호가 달라질 수 있습니다</td></tr>
          </tbody>
        </table>
      </Card>

      <LockScenarios />
      <LockCustom />
      <LockResults />
    </div>
    <PageNav title="DB 락 전략" :items="sections" />
  </div>
</template>
