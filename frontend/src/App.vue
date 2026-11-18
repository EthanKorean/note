<script setup lang="ts">
import { onMounted, reactive, watch } from 'vue'
import { RouterLink, RouterView, useRoute } from 'vue-router'
import { menu } from './menu'
import { useToastStore } from './stores/toast'
import WsPanel from './components/WsPanel.vue'
import ToastHost from './components/ToastHost.vue'

const toast = useToastStore()
onMounted(() => toast.connect()) // claude-toast 알림은 어느 화면에서든 받는다

/** 메뉴 그룹 접기/펼치기. 현재 화면이 속한 그룹은 항상 펼치고, 나머지는 마지막에 정한 상태를 기억한다. */
const KEY = 'note.menu.collapsed'
function load(): Record<string, boolean> {
  try {
    const v: unknown = JSON.parse(localStorage.getItem(KEY) ?? '{}')
    if (typeof v !== 'object' || v === null || Array.isArray(v)) return {} // 모양이 다른 값(null, 숫자 등)이 들어 있어도 화면이 깨지지 않게
    return Object.fromEntries(Object.entries(v).filter(([, x]) => typeof x === 'boolean')) as Record<string, boolean>
  } catch {
    return {}
  }
}
const collapsed = reactive<Record<string, boolean>>(load())
function save() {
  try {
    localStorage.setItem(KEY, JSON.stringify(collapsed))
  } catch {
    /* 저장 실패해도 화면은 동작한다 */
  }
}
function toggle(title: string) {
  collapsed[title] = !collapsed[title]
  save()
}
const route = useRoute()
watch(
  () => route.path,
  (path) => {
    const g = menu.find((m) => m.items.some((i) => i.path === path))
    if (g?.title && collapsed[g.title]) {
      collapsed[g.title] = false
      save() // 자동으로 펼친 것도 저장한다(저장하지 않으면 다음에 다른 그룹을 눌러 저장할 때 이 변경만 슬쩍 반영된다)
    }
  },
  { immediate: true },
)
</script>

<template>
  <div class="shell">
    <nav class="side" aria-label="메뉴">
      <RouterLink to="/" class="brand">Note</RouterLink>
      <div v-for="(g, gi) in menu" :key="gi" class="group">
        <button v-if="g.title" type="button" class="group-title" :aria-expanded="!collapsed[g.title]" @click="toggle(g.title)">
          <span>{{ g.title }}</span><span class="chev" aria-hidden="true">{{ collapsed[g.title] ? '▸' : '▾' }}</span>
        </button>
        <div v-show="!g.title || !collapsed[g.title]" class="group-items">
          <template v-for="i in g.items" :key="i.path">
            <span v-if="i.soon" class="item soon">{{ i.label }}<small>준비 중</small></span>
            <RouterLink v-else :to="i.path" class="item" :class="{ sub: g.title }" active-class="on" exact-active-class="on">{{ i.label }}</RouterLink>
          </template>
        </div>
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
