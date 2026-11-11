<script setup lang="ts">
import Card from './Card.vue'
import { summaryConditions, summaryFindings, summaryGuide, summaryRows } from '../resultSummary'
</script>

<template>
  <Card id="card-summary" title="결과 정리">
    <h3 class="sum-h">측정 조건</h3>
    <ul class="legend sum-list">
      <li v-for="t in summaryConditions" :key="t">{{ t }}</li>
    </ul>

    <h3 class="sum-h">방식별 결과 (총 소요 짧은 순)</h3>
    <div class="scroll">
      <table>
        <thead>
          <tr>
            <th class="num">순위</th>
            <th>방식</th>
            <th>배치 크기</th>
            <th>총 소요</th>
            <th>속도 (건/초)</th>
            <th>비고</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(r, i) in summaryRows" :key="r.method">
            <td class="num rank">{{ i + 1 }}</td>
            <td>
              {{ r.label }}
              <small class="code-name">{{ r.method }}</small>
            </td>
            <td>{{ r.batch }}</td>
            <td class="strong">{{ r.total }}</td>
            <td>{{ r.speed }}</td>
            <td class="muted">{{ r.note }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <h3 class="sum-h">알게 된 점</h3>
    <ul class="legend sum-list">
      <li v-for="t in summaryFindings" :key="t">{{ t }}</li>
    </ul>

    <h3 class="sum-h">선택 기준</h3>
    <ul class="legend sum-list">
      <li v-for="t in summaryGuide" :key="t">{{ t }}</li>
    </ul>
  </Card>
</template>
