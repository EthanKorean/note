<script setup lang="ts">
import { methodSamples } from '../methodSamples'
import Card from './Card.vue'

const methods = methodSamples.filter((m) => m.notes)
</script>

<template>
  <Card id="card-notes" title="방식별 장단점 · 느낀점">
    <p class="muted notes-intro">수치는 이번 측정(642만 행, 인덱스 포함) 기준이고, 트랜잭션 설명 중 일반 동작은 이 프로젝트에서 직접 시험하지 않은 것이 있습니다.</p>
    <section v-for="m in methods" :key="m.value" class="notes" :aria-label="m.label">
      <h3>
        {{ m.label }}
        <small class="code-name inline">{{ m.value }}</small>
      </h3>
      <div class="notes-grid">
        <div>
          <h4 class="pros">장점</h4>
          <ul><li v-for="t in m.notes!.pros" :key="t">{{ t }}</li></ul>
        </div>
        <div>
          <h4 class="cons">단점</h4>
          <ul><li v-for="t in m.notes!.cons" :key="t">{{ t }}</li></ul>
        </div>
      </div>
      <h4>느낀점</h4>
      <ul><li v-for="t in m.notes!.feel" :key="t">{{ t }}</li></ul>
      <h4>트랜잭션 관리</h4>
      <ul><li v-for="t in m.notes!.tx" :key="t">{{ t }}</li></ul>
    </section>
  </Card>
</template>
