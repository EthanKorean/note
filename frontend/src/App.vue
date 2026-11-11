<script setup lang="ts">
import { RouterLink, RouterView } from 'vue-router'
import { menu } from './menu'
import WsPanel from './components/WsPanel.vue'
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
    </nav>
    <main>
      <RouterView />
    </main>
  </div>
  <WsPanel />
</template>
