<script setup lang="ts">
import { onMounted } from 'vue'
import { RouterLink, RouterView } from 'vue-router'
import { menu } from './menu'
import { useToastStore } from './stores/toast'
import WsPanel from './components/WsPanel.vue'
import ToastHost from './components/ToastHost.vue'

const toast = useToastStore()
onMounted(() => toast.connect()) // claude-toast 알림은 어느 화면에서든 받는다
</script>

<template>
  <div class="shell">
    <nav class="side" aria-label="메뉴">
      <RouterLink to="/" class="brand">Note</RouterLink>
      <div v-for="(g, gi) in menu" :key="gi" class="group">
        <div v-if="g.title" class="group-title">{{ g.title }}</div>
        <template v-for="i in g.items" :key="i.path">
          <span v-if="i.soon" class="item soon">{{ i.label }}<small>준비 중</small></span>
          <RouterLink v-else :to="i.path" class="item" active-class="on" exact-active-class="on">{{ i.label }}</RouterLink>
        </template>
      </div>
      <button v-if="toast.permission === 'default'" type="button" class="ghost notify-opt" @click="toast.requestPermission()">브라우저 알림 허용</button>
    </nav>
    <main>
      <RouterView />
    </main>
  </div>
  <WsPanel />
  <ToastHost />
</template>
