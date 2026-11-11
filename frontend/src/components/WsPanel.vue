<script setup lang="ts">
import { onMounted } from 'vue'
import { storeToRefs } from 'pinia'
import { useWsStore } from '../stores/ws'

const ws = useWsStore()
const { connected, messages, unread, panelOpen } = storeToRefs(ws)

onMounted(() => ws.connect())
</script>

<template>
  <div class="ws">
    <section v-if="panelOpen" class="ws-panel" aria-label="웹소켓 메시지">
      <header>
        <strong>실시간 메시지</strong>
        <span class="ws-state" :class="{ on: connected }">{{ connected ? '연결됨' : '연결 중…' }}</span>
        <button type="button" class="ghost" @click="ws.clear()">지우기</button>
      </header>
      <ul v-if="messages.length">
        <li v-for="(m, i) in messages" :key="i" :class="m.level">
          <time>{{ m.time }}</time>
          <span class="m">{{ m.method }}</span>
          <span>{{ m.message }}</span>
        </li>
      </ul>
      <p v-else class="muted empty">적재를 실행하면 진행 단계가 여기에 실시간으로 표시됩니다.</p>
    </section>

    <button type="button" class="ws-fab" :aria-label="`실시간 메시지 ${unread}건`" :aria-expanded="panelOpen" @click="ws.togglePanel()">
      <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
      </svg>
      <span class="dot" :class="{ on: connected }" />
      <span v-if="unread" class="badge">{{ unread > 99 ? '99+' : unread }}</span>
    </button>
  </div>
</template>
