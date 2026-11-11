<script setup lang="ts">
import { nextTick, onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps<{ title: string; items: { id: string; label: string }[] }>()
const active = ref(props.items[0]?.id ?? '')
let observer: IntersectionObserver | null = null

onMounted(() => {
  // 화면 위쪽 1/3 지점을 지나는 카드를 현재 위치로 본다.
  observer = new IntersectionObserver(
    (entries) => {
      for (const e of entries) if (e.isIntersecting) active.value = e.target.id
    },
    { rootMargin: '-15% 0px -70% 0px' },
  )
  props.items.forEach((i) => {
    const el = document.getElementById(i.id)
    if (el) observer?.observe(el)
  })
})
onBeforeUnmount(() => observer?.disconnect())

async function go(id: string) {
  window.dispatchEvent(new CustomEvent('open-card', { detail: id }))
  await nextTick()
  active.value = id
  document.getElementById(id)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}
</script>

<template>
  <nav class="toc" :aria-label="`${title} 목차`">
    <div class="toc-title">{{ title }}</div>
    <button v-for="i in items" :key="i.id" type="button" :class="{ on: active === i.id }"
            :aria-current="active === i.id ? 'location' : undefined" @click="go(i.id)">
      {{ i.label }}
    </button>
  </nav>
</template>
