<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'

const props = defineProps<{ id: string; title: string }>()

// 항상 펼친 상태로 시작한다(접힘 상태는 저장하지 않는다).
const open = ref(true)

function set(v: boolean) {
  open.value = v
}

/** 오른쪽 내비게이션에서 이동할 때 접혀 있으면 펼친다. */
function onOpenCard(e: Event) {
  if ((e as CustomEvent<string>).detail === props.id) set(true)
}
onMounted(() => window.addEventListener('open-card', onOpenCard))
onBeforeUnmount(() => window.removeEventListener('open-card', onOpenCard))
</script>

<template>
  <section :id="id" class="card">
    <div class="head">
      <button class="head-toggle" type="button" :aria-expanded="open" :aria-controls="`${id}-body`" @click="set(!open)">
        <span class="chev" :class="{ open }" aria-hidden="true">▸</span>
        <h2>{{ title }}</h2>
      </button>
      <div v-if="open" class="head-actions"><slot name="actions" /></div>
    </div>
    <div v-show="open" :id="`${id}-body`" class="card-body"><slot /></div>
    <footer v-if="open && $slots.footer" class="card-foot"><slot name="footer" /></footer>
  </section>
</template>
