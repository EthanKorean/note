<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { RADII, drawBall } from '../game/suika'

/** 게임 판의 공과 같은 그림(drawBall)을 캔버스로 그리는 작은 아이콘. */
const props = defineProps<{
  level: number
  /** 지정하면 이 지름(px)으로 고정, 없으면 게임 공 크기 비율(scale)로 그린다. */
  size?: number
  scale?: number
}>()

const cv = ref<HTMLCanvasElement>()
// 범위 밖 단계가 들어와도 NaN 크기가 되지 않도록 0~최대 단계로 맞춘다.
const lv = computed(() => Math.min(Math.max(Math.trunc(props.level) || 0, 0), RADII.length - 1))
const px = computed(() => Math.max(6, props.size ?? Math.round(RADII[lv.value] * 2 * (props.scale ?? 0.34) * 10) / 10))

function draw() {
  const el = cv.value
  const ctx = el?.getContext('2d')
  if (!el || !ctx) return
  const dpr = window.devicePixelRatio || 1
  el.width = Math.round(px.value * dpr) // 크기를 바꾸면 캔버스가 지워지므로 항상 다시 그린다
  el.height = Math.round(px.value * dpr)
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
  // 토큰을 못 읽으면 글자색으로 대신해 엉뚱한 색이 남지 않게 한다.
  const color = getComputedStyle(el).getPropertyValue(`--fruit-${lv.value + 1}`).trim() || 'currentColor'
  const shine = getComputedStyle(el).getPropertyValue('--ball-shine').trim()
  drawBall(ctx, px.value / 2, px.value / 2, px.value / 2, color, shine)
}

// 라이트/다크 전환 시 CSS 토큰 색이 바뀌므로 다시 그린다.
const mq = window.matchMedia('(prefers-color-scheme: dark)')
onMounted(() => {
  draw()
  mq.addEventListener('change', draw)
})
onBeforeUnmount(() => mq.removeEventListener('change', draw))
watch(() => [lv.value, px.value], async () => { await nextTick(); draw() })
</script>

<template>
  <canvas ref="cv" class="ball-icon" role="img" :aria-label="`${lv + 1}단계 공`" :style="{ width: px + 'px', height: px + 'px' }" />
</template>

<style scoped>
.ball-icon { display: block; flex: none; }
</style>
