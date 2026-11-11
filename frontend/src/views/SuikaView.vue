<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import Card from '../components/Card.vue'
import PageNav from '../components/PageNav.vue'
import StepList from '../components/StepList.vue'
import BallIcon from '../components/BallIcon.vue'
import { rules, steps } from '../game/impl'
import { RADII, Suika } from '../game/suika'

const sections = [
  { id: 'card-game', label: '게임' },
  { id: 'card-rules', label: '조작·규칙' },
  { id: 'card-how', label: '동작 원리' },
  { id: 'card-conclusion', label: '결론' },
]

const root = ref<HTMLElement>()
const canvas = ref<HTMLCanvasElement>()
const bouncy = ref(false)
watch(bouncy, (v) => { if (game) game.bouncy = v })
const ui = reactive({
  score: 0,
  best: 0,
  next: 0,
  over: false,
  drops: 0,
  made: [] as number[],
})
let game: Suika | null = null

function sync() {
  if (!game) return
  ui.score = game.score
  ui.best = game.best
  ui.next = game.next
  ui.over = game.over
  ui.drops = game.drops
  ui.made = [...game.made]
}

/**
 * 마우스로 버튼·체크박스를 누른 뒤에는 포커스를 풀어, 이어서 스페이스바로 바로 투하할 수 있게 한다
 * (포커스가 남으면 스페이스가 그 컨트롤을 다시 누른다). 키보드로 누른 경우(click 의 detail === 0)는
 * 포커스를 유지해 키보드 사용자가 위치를 잃지 않게 한다.
 */
function releaseFocusAfterMouse(e: MouseEvent) {
  if (e.detail === 0) return // 키보드(Enter/Space)로 일어난 click
  if (!root.value?.contains(e.target as Node)) return // 이 페이지 안의 컨트롤만(다른 화면의 버튼은 건드리지 않는다)
  // 라벨을 눌렀을 때는 포커스가 체크박스로 옮겨 간 뒤에 풀어야 하므로 한 틱 늦춘다.
  setTimeout(() => {
    const a = document.activeElement as HTMLElement | null
    if (a?.matches('button, input[type="checkbox"]')) a.blur()
  })
}

/** 판이 화면에 보일 때만 스페이스바를 게임 입력으로 쓴다(다른 카드를 읽을 때는 평소처럼 스크롤). */
function boardVisible() {
  const r = canvas.value?.getBoundingClientRect()
  return !!r && r.width > 0 && r.bottom > 0 && r.top < window.innerHeight
}

function onKey(e: KeyboardEvent) {
  if (e.code !== 'Space' || !game) return
  if (e.repeat || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) return // 누르고 있어도 한 번만
  const t = e.target as HTMLElement
  if (['BUTTON', 'INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName) || t.isContentEditable) return
  if (!boardVisible()) return
  e.preventDefault()
  game.drop()
}

// 왼쪽 버튼(터치 포함)으로 판에서 눌렀다가 판에서 뗄 때만 투하한다.
let pressed = false
function onDown(e: PointerEvent) {
  if (e.button !== 0) return
  pressed = true
  game?.aim(e.clientX)
}
function onUp(e: PointerEvent) {
  if (e.button !== 0 || !pressed) return
  pressed = false
  game?.aim(e.clientX)
  game?.drop()
}
const onCancel = () => { pressed = false }

onMounted(() => {
  game = new Suika(canvas.value!, sync)
  sync() // 생성 중의 콜백은 game 대입 전이라 건너뛰므로, 저장된 최고 점수를 화면에 반영한다
  game.start()
  window.addEventListener('keydown', onKey)
  window.addEventListener('click', releaseFocusAfterMouse, true)
})
onBeforeUnmount(() => {
  game?.stop()
  window.removeEventListener('keydown', onKey)
  window.removeEventListener('click', releaseFocusAfterMouse, true)
})

</script>

<template>
  <div ref="root" class="with-toc">
    <div class="page">
      <header>
        <h1>수박 게임</h1>
        <p class="muted">같은 공끼리 붙이면 다음 단계로 합쳐집니다. 이미지 없이 원만 그려서 만들었습니다.</p>
      </header>

      <Card id="card-game" title="게임">
        <div class="suika">
          <canvas ref="canvas" class="board" aria-label="수박 게임 판"
                  @pointermove="game?.aim($event.clientX)" @pointerdown="onDown" @pointerup="onUp"
                  @pointercancel="onCancel" @pointerleave="onCancel" />
          <div class="panel">
            <div class="stats">
              <div class="stat"><span class="muted">점수</span><b>{{ ui.score }}</b></div>
              <div class="stat"><span class="muted">최고</span><b>{{ ui.best }}</b></div>
              <div class="stat"><span class="muted">다음</span><span class="next"><BallIcon :level="ui.next" :scale="0.6" /></span></div>
            </div>
            <button v-if="ui.over" class="primary restart" type="button" @click="game?.reset()">다시 시작</button>
            <div class="order">
              <span class="muted">합치는 순서 (작은 → 큰) · 아래 숫자는 이번 판에서 만든 횟수</span>
              <ol class="chain">
                <li v-for="(_, i) in RADII" :key="i">
                  <BallIcon :level="i" />
                  <small class="count" :class="{ zero: !ui.made[i] }">{{ ui.made[i] ?? 0 }}</small>
                </li>
              </ol>
            </div>
            <ul class="legend">
              <li><b>조준</b>마우스(터치)를 좌우로 움직인다</li>
              <li><b>투하</b>클릭(터치는 뗄 때) 또는 스페이스바</li>
              <li><b>합치기</b>같은 색 둘이 닿으면 한 단계 큰 원이 된다</li>
              <li><b>종료</b>점선 위로 2초 넘게 걸쳐 있으면 끝</li>
            </ul>
          </div>
        </div>
        <template #footer>
          <div class="foot-grid">
            <div class="foot-checks bounce">
              <label><input v-model="bouncy" type="checkbox" /> 튕기기 (바닥·벽·공끼리 탄성)</label>
            </div>
            <button class="primary" type="button" @click="game?.reset()">새 게임</button>
          </div>
        </template>
      </Card>

      <Card id="card-rules" title="조작·규칙">
        <StepList id="rule" :steps="rules" />
      </Card>

      <Card id="card-how" title="동작 원리">
        <StepList id="how" :steps="steps" />
      </Card>

      <Card id="card-conclusion" title="결론">
        <h3 class="sub">내 소감</h3>
        <ul class="legend story">
          <li>수식이 가장 고민이었다. 내 능력 밖의 문제였던 것 같다.</li>
          <li>게임이 단순해도 시나리오별로 계산하는 부분이 생각보다 많았다.</li>
          <li>가장 궁금했던 건 원과 원의 충돌을 어떻게 계산하느냐였다.</li>
          <li>AI 없이는 하기 힘든 작업임을 느꼈다.</li>
        </ul>
        <h3 class="sub">AI(Claude)의 소감</h3>
        <ul class="legend story">
          <li>수식 자체는 강체 충돌의 표준 공식(겹침 판정, 위치 보정, 충격량, 쿨롱 마찰)을 2D 원에 맞춘 것이라 새로 만들어 낼 건 없었다. 어려운 쪽은 이걸 한꺼번에 돌렸을 때 공이 안정적으로 쌓이게 만드는 일이었다.</li>
          <li>공식보다 시간을 더 쓴 건 안정적으로 보이게 하는 보정이었다. 낮은 반발 계수, 느린 속도는 정지, 충돌 4번 반복, 정중앙 균형 깨기가 그것이다.</li>
          <li>"1번 공 위에 4번 공을 떨어뜨리면 굴러 떨어지지 않나?"라는 질문이 가장 값졌다. 실제로 정중앙이면 영원히 올라탄 채 멈추는 버그였는데, 코드만 읽어서는 내가 먼저 찾지 못했을 것이다.</li>
          <li>버그는 물리 계산보다 화면·입력과 만나는 경계에서 더 많이 나왔다. 접힌 카드의 폭 0이 NaN으로 번지고, 포커스가 스페이스를 가로채고, 생성 중 콜백이 빠졌다. 코드 리뷰를 5번 반복해서 잡았다.</li>
          <li>직접 손으로 플레이해 본 것은 아니다. 구르는 느낌과 마찰·반발 값은 Playwright로 숫자를 재서 확인했을 뿐이라, 손맛에 맞춘 조정은 직접 해 보시고 값을 바꿔야 할 수 있다.</li>
          <li>화면에 보여 주는 코드(`impl.ts`)가 실제 코드의 복사본이라 코드를 고칠 때마다 어긋났고, 리뷰에서 가장 자주 지적됐다. 같은 내용을 두 곳에 두는 구조의 비용이다.</li>
        </ul>
      </Card>
    </div>
    <PageNav title="수박 게임" :items="sections" />
  </div>
</template>

<style scoped>
.suika { display: grid; grid-template-columns: minmax(0, 346px) minmax(0, 1fr); gap: 24px; align-items: start; }
.board { width: min(100%, calc(500px * 360 / 520)); height: auto; aspect-ratio: 360 / 520; /* 높이 최대 500px, 비율 유지 */ border: 1px solid var(--border); border-radius: 8px; touch-action: none; cursor: pointer; }
.panel { display: flex; flex-direction: column; gap: 18px; min-width: 0; }
.stats { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.stat { display: flex; flex-direction: column; gap: 2px; padding: 12px 14px; background: var(--tag-bg); border-radius: 8px; font-size: 0.85rem; }
.restart { align-self: flex-start; min-width: 140px; }
.order { display: flex; flex-direction: column; gap: 10px; font-size: 0.85rem; }
.stat b { font-size: 1.6rem; line-height: 1.2; font-variant-numeric: tabular-nums; }
.next { display: flex; align-items: center; gap: 8px; font-weight: 600; }
.chain { list-style: none; margin: 0; padding: 0; display: flex; flex-wrap: wrap; gap: 10px; align-items: flex-end; }
.chain li { display: flex; flex-direction: column; align-items: center; gap: 4px; }
.count { font-size: 0.72rem; font-variant-numeric: tabular-nums; color: var(--text); font-weight: 600; }
.count.zero { color: var(--muted); font-weight: 400; }
@media (max-width: 760px) { .suika { grid-template-columns: minmax(0, 1fr); } }
.bounce { padding-top: 0; align-self: center; }
.sub { margin: 14px 0 0; font-size: 0.95rem; }
.sub:first-child { margin-top: 0; }
.story li::before { content: '-'; flex: none; color: var(--muted); }
</style>
