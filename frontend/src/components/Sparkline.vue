<script setup lang="ts">
import { computed } from 'vue'

/** 0~1 값 배열을 작은 선 그래프로 그린다(적중률 추이). */
const props = defineProps<{ values: number[]; label?: string }>()

const W = 120
const H = 28
const points = computed(() => {
  const n = props.values.length
  if (n < 2) return ''
  return props.values.map((v, i) => `${((i / (n - 1)) * W).toFixed(1)},${(H - 2 - v * (H - 4)).toFixed(1)}`).join(' ')
})
</script>

<template>
  <svg class="spark" :viewBox="`0 0 ${W} ${H}`" role="img" :aria-label="label ?? '추이 그래프'">
    <line x1="0" :y1="H - 2" :x2="W" :y2="H - 2" class="spark-base" />
    <polyline v-if="points" :points="points" class="spark-line" />
  </svg>
</template>
