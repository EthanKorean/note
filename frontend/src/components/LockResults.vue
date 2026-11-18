<script setup lang="ts">
import { computed } from 'vue'
import { storeToRefs } from 'pinia'
import { useLockStore } from '../stores/lock'
import Card from './Card.vue'
import LockTable from './LockTable.vue'

const store = useLockStore()
const { runs } = storeToRefs(store)
/** 표에 그리는 최대 행 수(서버에는 더 쌓여 있을 수 있다). */
const SHOWN = 100
const shown = computed(() => runs.value.slice(0, SHOWN))

function clearAll() {
  if (confirm('저장된 실험 결과를 모두 삭제합니다. 계속할까요?')) store.clearRuns()
}
</script>

<template>
  <Card id="card-results" title="실험 기록">
    <template #actions>
      <button v-if="runs.length" class="ghost" @click="clearAll">기록 지우기</button>
    </template>

    <p v-if="!runs.length" class="muted">아직 실험 기록이 없습니다. 위에서 실험을 실행해 보세요.</p>
    <LockTable v-else :items="shown.map((r) => ({ id: r.id, run: r }))" />
    <p v-if="runs.length > shown.length" class="muted">최근 {{ shown.length }}건만 보여 줍니다(저장된 기록은 {{ runs.length }}건).</p>

    <table class="kv">
      <tbody>
        <tr><th scope="row">재고 검증</th><td>끝난 뒤 DB 에 남은 값으로 확인합니다. "처음 재고 - 팔린 수량 = 남은 재고"이고 재고가 음수가 아니며 주문 수가 성공 수와 같으면 맞음입니다</td></tr>
        <tr><th scope="row">품절 / 실패</th><td>품절은 재고가 없어서 정상적으로 거절된 주문, 실패는 오류로 끝난 주문(데드락으로 취소, 락 대기 초과, 재시도 초과)입니다</td></tr>
        <tr><th scope="row">데드락</th><td>DB 가 데드락을 발견해 한쪽을 취소한 횟수(오류 1213). 다시 시도하는 방식은 취소될 때마다 셉니다</td></tr>
        <tr><th scope="row">저장</th><td>결과는 서버(test_result 테이블)에 저장되어 새로고침해도 유지됩니다</td></tr>
      </tbody>
    </table>
  </Card>
</template>
