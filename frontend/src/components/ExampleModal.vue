<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import CodeBlock from './CodeBlock.vue'
import type { Demo, DemoBlock } from '../aiDemos'
import type { CodeBlock as CodeBlockData } from '../methodSamples'

const props = defineProps<{ term: string; demo: Demo }>()
const emit = defineEmits<{ close: [] }>()

const dlg = ref<HTMLDialogElement | null>(null)
// 브라우저의 <dialog> 를 쓰면 Esc 로 닫기, 배경 가리기, 포커스 가두기가 기본으로 된다.
// 하지만 뒤의 페이지 스크롤은 막아 주지 않아서, 모달 안에서 끝까지 내리면 뒤의 페이지가 같이 내려간다.
// 열려 있는 동안 페이지 스크롤을 잠근다. 스크롤바가 사라지며 화면이 옆으로 밀리지 않게 그 자리를 남겨 둔다(scrollbar-gutter).
const root = document.documentElement
const prev = { overflow: root.style.overflow, gutter: root.style.scrollbarGutter }
onMounted(() => {
  root.style.scrollbarGutter = 'stable'
  root.style.overflow = 'hidden'
  dlg.value?.showModal()
})
onBeforeUnmount(() => {
  root.style.overflow = prev.overflow
  root.style.scrollbarGutter = prev.gutter
})

/** 마우스를 누른 곳이 배경이었는지. 모달 안에서 글자를 드래그하다 배경에서 놓아도 닫히지 않게, 누른 곳과 뗀 곳이 모두 배경일 때만 닫는다. */
let downOnBackdrop = false

function close() {
  dlg.value?.close()
}

/** 코드 블록으로 보여 줄 수 있는 언어만 CodeBlock 에 넘긴다(text 는 일반 글로 보여 준다). 타입 가드라서 형 변환 없이 검사된다. */
const isCode = (b: DemoBlock): b is DemoBlock & CodeBlockData => b.lang !== 'text'

/** 글로 보여 줄 블록(문서)은 왼쪽에, 코드 블록은 오른쪽에 둔다. */
const textBlocks = computed(() => props.demo.blocks.filter((b) => b.lang === 'text'))
const codeBlocks = computed(() => props.demo.blocks.filter(isCode))
</script>

<template>
  <!-- 배경(::backdrop)을 누르면 dialog 자신이 클릭 대상이 되므로 .self 로 닫는다. -->
  <dialog ref="dlg" class="demo-modal" aria-labelledby="demo-title" @close="emit('close')" @mousedown="downOnBackdrop = $event.target === dlg" @click.self="downOnBackdrop && close()">
    <div class="demo-body">
      <header class="demo-head">
        <h2 id="demo-title">{{ term }} — {{ demo.title }}</h2>
        <button type="button" class="ghost" @click="close">닫기</button>
      </header>

      <table class="kv">
        <tbody>
          <tr><th scope="row">예제 주제</th><td>해든 카페 안내 챗봇 (모든 카페 예제가 같은 카페를 쓴다). 이 노트 프로젝트와 세부가 다른 점은 용어 카드의 "프로젝트에서는" 줄에 있다</td></tr>
          <tr><th scope="row">상황</th><td>{{ demo.situation }}</td></tr>
          <tr v-if="demo.kind === 'claude'" class="warn-text">
            <th scope="row">주의</th>
            <td>Claude API를 쓰는 예제입니다. API 키가 없어서 이 프로젝트에서 실행해 보지 않았고, Claude Java SDK 문서(3.3.0 기준)에 있는 형태로 작성했습니다. 쓰려면 ANTHROPIC_API_KEY가 필요합니다. 코드는 핵심만 보이는 조각이라 client, cafeDoc 같은 변수는 앞에서 만든 것으로 가정합니다</td>
          </tr>
          <tr v-else class="muted"><th scope="row">종류</th><td>이 프로젝트에 실제로 있는 방식을 카페 예로 설명한 것입니다. 코드는 이 프로젝트 코드를 줄인 것입니다</td></tr>
        </tbody>
      </table>

      <!-- 넓은 화면에서는 왼쪽에 읽을 것(순서, 문서), 오른쪽에 코드와 출력을 나란히 둔다. 좁은 화면에서는 위아래로 쌓인다. -->
      <div class="demo-cols">
        <section class="col col-left" aria-label="설명과 문서">
          <h3 class="demo-h">순서</h3>
          <ol class="demo-steps">
            <li v-for="(s, i) in demo.steps" :key="i">{{ s }}</li>
          </ol>

          <figure v-for="b in textBlocks" :key="b.title" class="plain">
            <figcaption>{{ b.title }}</figcaption>
            <pre>{{ b.code }}</pre>
          </figure>

          <table v-if="demo.note || demo.link" class="kv">
            <tbody>
              <tr v-if="demo.note"><th scope="row">알아 둘 점</th><td>{{ demo.note }}</td></tr>
              <tr v-if="demo.link"><th scope="row">직접 해 보기</th><td><RouterLink :to="demo.link.path" @click="close">{{ demo.link.label }}</RouterLink></td></tr>
            </tbody>
          </table>
        </section>

        <section class="col col-right" aria-label="코드와 출력">
          <CodeBlock v-for="b in codeBlocks" :key="b.title" :block="b" />

          <figure v-if="demo.output" class="plain out">
            <figcaption>{{ demo.output.title }}</figcaption>
            <pre>{{ demo.output.text }}</pre>
          </figure>
        </section>
      </div>
    </div>
  </dialog>
</template>

<style scoped>
.demo-modal {
  width: min(1440px, 96vw);
  max-height: 90vh;
  padding: 0;
  border: 1px solid var(--border);
  border-radius: 12px;
  background: var(--surface);
  color: var(--text);
  overflow: auto;
  /* 모달의 끝에 닿은 뒤에도 스크롤을 계속하면 뒤의 페이지로 넘어가는 것(스크롤 체이닝)을 막는다 */
  overscroll-behavior: contain;
}
.demo-modal::backdrop { background: var(--game-overlay); } /* 어두운 막(라이트/다크 토큰) */
.demo-body { padding: 20px 24px 24px; display: flex; flex-direction: column; gap: 12px; }
.demo-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; position: sticky; top: -20px; background: var(--surface); padding: 20px 0 8px; margin-top: -20px; z-index: 1; }
.demo-head h2 { margin: 0; font-size: 1.25rem; }
.demo-h { margin: 0; font-size: 1rem; }
.demo-cols { display: grid; grid-template-columns: minmax(0, 5fr) minmax(0, 7fr); gap: 16px; align-items: start; }
.col { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
/* 1100px 보다 좁으면 코드를 읽을 폭이 모자라 위아래로 쌓는다 */
@media (max-width: 1100px) { .demo-cols { grid-template-columns: minmax(0, 1fr); } }
.demo-steps { margin: 0; padding-left: 22px; display: flex; flex-direction: column; gap: 4px; }
.plain { margin: 0; border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
.plain figcaption { padding: 6px 12px; font-size: 1rem; color: var(--muted); background: var(--tag-bg); border-bottom: 1px solid var(--border); }
.plain pre { margin: 0; padding: 12px 14px; white-space: pre-wrap; word-break: break-word; font-family: ui-monospace, 'SF Mono', Menlo, Consolas, monospace; font-size: 1rem; line-height: 1.55; overflow-x: auto; }
.out { border-style: dashed; }
</style>
