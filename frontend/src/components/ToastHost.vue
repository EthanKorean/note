<script setup lang="ts">
import { storeToRefs } from 'pinia'
import { useToastStore } from '../stores/toast'

const store = useToastStore()
const { toasts } = storeToRefs(store)

/** 토스트를 누르면 닫는다. 글자를 드래그해 고른 직후에는 닫지 않는다(복사하려는 것일 수 있어서). */
function close(id: string) {
  if (window.getSelection()?.toString()) return
  store.dismiss(id)
}
</script>

<template>
  <div class="toast-host" aria-live="polite">
    <TransitionGroup name="toast">
      <article
        v-for="t in toasts"
        :key="t.id"
        class="toast"
        :class="`kind-${t.kind}`"
        :role="t.kind === 'attention' || t.kind === 'error' ? 'alert' : 'status'"
        tabindex="0"
        title="누르면 닫힙니다"
        @click="close(t.id)"
        @keydown.enter="store.dismiss(t.id)"
        @keydown.esc="store.dismiss(t.id)"
      >
        <div class="toast-head">
          <strong>{{ t.title }}</strong>
          <span v-if="t.project" class="tag">{{ t.project }}</span>
        </div>
        <p v-if="t.message">{{ t.message }}</p>
      </article>
    </TransitionGroup>
  </div>
</template>
