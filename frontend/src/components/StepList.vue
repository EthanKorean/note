<script setup lang="ts">
import { ref } from 'vue'
import CodeBlock from './CodeBlock.vue'
import type { Step } from '../game/impl'

const props = defineProps<{ id: string; steps: Step[] }>()

// 항목별로 따로 토글한다(여러 개 동시에 열 수 있음). 처음에는 모두 접혀 있다.
const opened = ref<Set<string>>(new Set())
function toggle(k: string) {
  const n = new Set(opened.value)
  if (!n.delete(k)) n.add(k)
  opened.value = n
}
const MARKS = '①②③④⑤⑥⑦⑧⑨⑩'
const mark = (i: number) => MARKS[i] ?? String(i + 1)
// 키에 공백·한글이 들어 있어도 올바른 id 가 되도록 인덱스로 만든다.
const bodyId = (i: number) => `${props.id}-body-${i}`
</script>

<template>
  <ul class="steps">
    <li v-for="(st, i) in steps" :key="st.key">
      <button class="step-head" type="button" :aria-expanded="opened.has(st.key)" :aria-controls="opened.has(st.key) ? bodyId(i) : undefined" @click="toggle(st.key)">
        <span class="chev" :class="{ open: opened.has(st.key) }" aria-hidden="true">▸</span>
        <b>{{ st.key }}</b>
        <span class="step-text">{{ st.summary }}</span>
      </button>
      <div v-if="opened.has(st.key)" :id="bodyId(i)" class="step-body">
        <ul class="legend">
          <li v-for="(n, i) in st.notes" :key="n"><b class="n">{{ mark(i) }}</b><span>{{ n }}</span></li>
        </ul>
        <CodeBlock v-for="b in st.blocks" :key="b.title" :block="b" />
      </div>
    </li>
  </ul>
</template>

<style scoped>
.steps { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.steps > li + li { border-top: 1px solid var(--border); }
.step-head { all: unset; box-sizing: border-box; width: 100%; display: flex; gap: 10px; align-items: baseline; padding: 10px 12px; cursor: pointer; font-size: 1rem; }
.step-head:hover { background: var(--tag-bg); }
.step-head:focus-visible { outline: 2px solid var(--accent); outline-offset: -2px; }
.step-head b { flex: none; min-width: 4.5em; }
.step-text { flex: 1; min-width: 0; color: var(--muted); }
.chev { flex: none; display: inline-block; transition: transform 0.15s; color: var(--muted); }
.chev.open { transform: rotate(90deg); }
.step-body .legend b.n { min-width: 1.4em; color: var(--accent); }
.step-body { padding: 4px 12px 14px 34px; display: flex; flex-direction: column; gap: 12px; }
</style>
