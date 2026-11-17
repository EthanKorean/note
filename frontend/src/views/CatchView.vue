<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { storeToRefs } from 'pinia'
import Card from '../components/Card.vue'
import PageNav from '../components/PageNav.vue'
import CatchBoard from '../components/CatchBoard.vue'
import { PALETTE, useCatchStore } from '../stores/catch'

const sections = [
  { id: 'card-lobby', label: '참가자' },
  { id: 'card-play', label: '그림판' },
  { id: 'card-rules', label: '규칙' },
]

const store = useCatchStore()
const { connected, state, chat, chatCount, myPid, word, error, deadline, joined, me, isHost, isDrawer } = storeToRefs(store)

const nickInput = ref(store.nick)
const guessText = ref('')
const color = ref(0)
const size = ref(7)
const eraser = ref(false)
const left = ref(0) // 남은 초
const chatEl = ref<HTMLElement>()
const board = ref<InstanceType<typeof CatchBoard>>()

const SIZES = [
  { v: 3, label: '가늘게' },
  { v: 7, label: '보통' },
  { v: 16, label: '굵게' },
]
const LAPS = [1, 2, 3]
const SECONDS = [30, 45, 60, 90, 120]

const phase = computed(() => state.value?.phase ?? 'LOBBY')
const players = computed(() => state.value?.players ?? [])
const inGame = computed(() => phase.value === 'DRAWING' || phase.value === 'REVEAL')
const lobbyLike = computed(() => phase.value === 'LOBBY' || phase.value === 'RESULT')
const canEditOptions = computed(() => joined.value && isHost.value && lobbyLike.value)
const drawer = computed(() => players.value.find((p) => p.pid === state.value?.drawerPid))
const solver = computed(() => players.value.find((p) => p.pid === state.value?.solverPid))
/** 결과 단계에서는 점수 순, 그 외에는 입장 순서. */
const rows = computed(() => (phase.value === 'RESULT' ? [...players.value].sort((a, b) => Number(a.left) - Number(b.left) || b.score - a.score) : players.value))
/** 결과 순위: 같은 점수는 같은 등수, 나간 사람은 순위 없이 표시만 한다. */
const ranks = computed(() => {
  const m = new Map<number, number>()
  let prev = -1, rank = 0, seen = 0
  for (const p of rows.value) {
    if (p.left) continue
    seen++
    if (p.score !== prev) { rank = seen; prev = p.score }
    m.set(p.pid, rank)
  }
  return m
})
const spectating = computed(() => !!me.value?.spectator && phase.value === 'DRAWING')
const canGuess = computed(() => connected.value && joined.value && !isDrawer.value && !spectating.value && !!state.value)
const liveCount = computed(() => players.value.filter((p) => !p.left).length)

const headline = computed(() => {
  const s = state.value
  if (!s) return connected.value ? '방 정보를 받는 중…' : '서버에 연결하는 중… (계속 안 되면 catch-service(9206)와 gateway 가 실행 중인지 확인하세요)'
  if (phase.value === 'LOBBY') return `참가자 ${liveCount.value}명 · 2명 이상이 모두 Ready 를 누르면 시작합니다`
  if (phase.value === 'RESULT') return '게임 종료 · 모두 Ready 를 누르면 새 게임이 시작됩니다'
  if (phase.value === 'REVEAL') {
    return solver.value ? `정답은 "${s.revealWord}" — ${solver.value.nick}님이 맞혔습니다 (+${s.win})` : `정답은 "${s.revealWord}" — 아무도 맞히지 못했습니다`
  }
  if (isDrawer.value) return `제시어: ${word.value} — 말없이 그림으로만 알려 주세요`
  return `${drawer.value?.nick ?? ''}님이 그리는 중 · ${Array(s.wordLen).fill('○').join(' ')} (${s.wordLen}글자)`
})

function tick() {
  left.value = deadline.value ? Math.max(0, Math.ceil((deadline.value - Date.now()) / 1000)) : 0
}
let tm = 0
watch(deadline, tick) // 새 라운드가 시작되는 순간 바로 남은 시간을 맞춘다(다음 틱까지 0초·빨간색이 보이지 않게)

function enter() {
  const n = nickInput.value.trim()
  if (n) store.join(n)
}

function send() {
  const t = guessText.value.trim()
  if (!t) return
  if (store.guess(t)) guessText.value = '' // 연결이 끊겨 못 보냈으면 입력을 남겨 둔다
}

function pickColor(i: number) {
  color.value = i
  eraser.value = false
}
// 다시 출제자가 될 때마다 기본 도구로 시작한다(지난번에 쓴 지우개가 켜진 채로 그려져 아무것도 안 보이는 일 방지).
watch(isDrawer, (v) => { if (v) { eraser.value = false; color.value = 0 } })
const drawColor = computed(() => (eraser.value ? -1 : color.value))

/** 선택창은 서버 값을 따라간다. 방장이 바꾸면 보내고, 못 보내거나 서버가 거절하면(상태가 다시 와서) 실제 값으로 돌아간다. */
const lapsSel = ref(1)
const secSel = ref(60)
let optTimer = 0 // 방장이 방금 바꾼 선택이 다른 상태 방송에 되돌려지지 않게, 잠시 동안은 서버 값으로 덮어쓰지 않는다
const syncOpts = () => { const s = state.value; if (s) { lapsSel.value = s.laps; secSel.value = s.seconds } }
watch(state, (s) => {
  // 방금 보낸 값이 서버 상태에 반영되어 돌아왔으면 더 기다릴 필요가 없다(느린 연결에서 옛 값으로 깜빡이지 않게).
  if (optTimer && s && s.laps === lapsSel.value && s.seconds === secSel.value) { window.clearTimeout(optTimer); optTimer = 0 }
  if (!optTimer) syncOpts()
}, { immediate: true })
function setOptions() {
  if (!store.options(lapsSel.value, secSel.value)) { syncOpts(); return }
  window.clearTimeout(optTimer)
  // 잠시 뒤에는 서버 값으로 맞춘다(서버가 거절했거나 다른 값이 반영된 경우도 실제 설정으로 돌아간다).
  optTimer = window.setTimeout(() => { optTimer = 0; syncOpts() }, 1500)
}

// 새 메시지가 오면 채팅을 맨 아래로 내린다.
watch(chatCount, async () => {
  const el = chatEl.value
  const nearBottom = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 40 // 위로 올려 읽는 중이면 그대로 둔다
  await nextTick()
  if (nearBottom && chatEl.value) chatEl.value.scrollTop = chatEl.value.scrollHeight
})

onMounted(() => {
  store.connect()
  tick()
  tm = window.setInterval(tick, 250)
})
onBeforeUnmount(() => {
  window.clearInterval(tm)
  store.disconnect()
})
</script>

<template>
  <div class="with-toc">
    <div class="page">
      <header>
        <h1>캐치마인드</h1>
        <p class="muted">돌아가며 제시어를 그림으로 그리고, 나머지는 채팅창에 정답을 입력합니다. 가장 먼저 맞힌 사람이 +50점.</p>
      </header>

      <Card id="card-lobby" title="참가자">
        <p v-if="error" class="err" role="alert">{{ error }}</p>
        <table v-if="players.length">
          <thead>
            <tr><th v-if="phase === 'RESULT'" class="num">순위</th><th>닉네임</th><th>상태</th><th class="num">점수</th></tr>
          </thead>
          <tbody>
            <tr v-for="p in rows" :key="p.pid" :class="{ gone: p.left, mine: p.pid === myPid }">
              <td v-if="phase === 'RESULT'" class="num">{{ ranks.get(p.pid) ?? '-' }}</td>
              <td>
                {{ p.nick }}
                <span v-if="p.pid === myPid" class="tag">나</span>
                <span v-if="p.host" class="tag host-tag">방장</span>
                <span v-if="phase === 'DRAWING' && !p.left && p.pid === state?.drawerPid" class="tag">그리는 중</span>
              </td>
              <td>
                <span v-if="p.left" class="muted">나감</span>
                <span v-else-if="!p.online" class="muted">연결 끊김</span>
                <span v-else-if="p.spectator && inGame" class="muted">관전</span>
                <span v-else-if="lobbyLike">{{ p.ready ? 'Ready' : '대기' }}</span>
                <span v-else-if="p.solved">정답</span>
                <span v-else class="muted">-</span>
              </td>
              <td class="num strong">{{ p.score }}</td>
            </tr>
          </tbody>
        </table>
        <p v-else class="muted">아직 아무도 없습니다. 닉네임을 정해 입장하세요.</p>

        <template #footer>
          <div v-if="!joined" class="foot-grid">
            <label class="foot-field">닉네임 (1~8자)
              <input v-model="nickInput" type="text" maxlength="8" :disabled="!connected" @keydown.enter="!$event.isComposing && enter()" />
            </label>
            <button class="primary" type="button" :disabled="!connected || !nickInput.trim()" @click="enter">입장</button>
          </div>
          <div v-else class="foot-grid">
            <label class="foot-field">바퀴 수
              <select v-model.number="lapsSel" :disabled="!canEditOptions" @change="setOptions">
                <option v-for="n in LAPS" :key="n" :value="n">{{ n }}바퀴</option>
              </select>
            </label>
            <label class="foot-field">라운드 시간
              <select v-model.number="secSel" :disabled="!canEditOptions" @change="setOptions">
                <option v-for="n in SECONDS" :key="n" :value="n">{{ n }}초</option>
              </select>
            </label>
            <span v-if="!isHost" class="muted opt-note">옵션은 방장만 바꿀 수 있습니다</span>
            <button v-if="lobbyLike" class="primary" type="button" :disabled="!connected" @click="store.ready(!me?.ready)">{{ me?.ready ? 'Ready 취소' : 'Ready' }}</button>
            <button class="ghost" type="button" :disabled="!connected" @click="store.leave()">나가기</button>
          </div>
        </template>
      </Card>

      <Card id="card-play" title="그림판">
        <div class="statusbar">
          <strong class="headline">{{ headline }}</strong>
          <span v-if="phase === 'DRAWING'" class="clock" :class="{ low: left <= 10 }">{{ left }}초</span>
          <span v-if="inGame && state" class="muted">{{ state.round }} / {{ state.total }} 라운드</span>
        </div>
        <div class="play">
          <div class="stage">
            <CatchBoard ref="board" :can-draw="isDrawer" :color="drawColor" :size="size" />
          </div>
          <div class="chatbox">
            <ul ref="chatEl" class="chatlog" aria-live="polite">
              <li v-for="(m, i) in chat" :key="chatCount - chat.length + i" :class="`k-${m.kind}`">
                <template v-if="m.kind === 'chat'"><b>{{ m.nick }}</b> {{ m.text }}</template>
                <template v-else>{{ m.text }}</template>
              </li>
              <li v-if="!chat.length" class="k-system">입장하면 채팅과 정답 입력이 여기에 표시됩니다.</li>
            </ul>
            <form class="say" @submit.prevent="send">
              <input v-model="guessText" type="text" maxlength="50" :disabled="!canGuess"
                       :placeholder="isDrawer ? '출제자는 말할 수 없습니다' : spectating ? '관전 중에는 입력할 수 없습니다' : joined ? '정답을 입력하세요' : '입장 후 입력할 수 있습니다'" />
              <button class="primary" type="submit" :disabled="!canGuess || !guessText.trim()">전송</button>
            </form>
          </div>
        </div>
        <template #footer>
        <div v-if="isDrawer" class="tools">
          <button v-for="i in PALETTE" :key="i" type="button" class="swatch" :class="{ on: !eraser && color === i - 1 }"
                  :style="{ background: `var(--draw-${i})` }" :aria-label="`색 ${i}`" :aria-pressed="!eraser && color === i - 1" @click="pickColor(i - 1)" />
          <button type="button" class="ghost" :class="{ on: eraser }" :aria-pressed="eraser" @click="eraser = !eraser">지우개</button>
          <span class="sep" />
          <button v-for="s in SIZES" :key="s.v" type="button" class="ghost" :class="{ on: size === s.v }" :aria-pressed="size === s.v" @click="size = s.v">{{ s.label }}</button>
          <span class="sep" />
          <button type="button" class="ghost danger" @click="board?.clearMine()">전체 지우기</button>
        </div>
          <ul v-if="!isDrawer" class="legend hint"><li>그림 도구(색·지우개·굵기·전체 지우기)는 그리는 사람에게만 나타납니다</li></ul>
        </template>
      </Card>

      <Card id="card-rules" title="규칙">
        <ul class="legend">
          <li><b>시작</b>2명 이상이 모두 Ready 를 누르면 자동으로 시작합니다</li>
          <li><b>순서</b>입장한 순서대로 한 번씩 그립니다. 바퀴 수만큼 반복합니다</li>
          <li><b>제시어</b>서버가 랜덤으로 고르고 출제자에게만 보냅니다. 다른 사람에게는 글자 수만 보입니다</li>
          <li><b>점수</b>채팅창에 정답을 가장 먼저 입력한 사람이 +50점. 시간이 끝나면 점수 없이 다음 사람으로 넘어갑니다</li>
          <li><b>옵션</b>바퀴 수와 라운드 시간은 방장(가장 먼저 들어온 사람)만 바꿀 수 있습니다</li>
          <li><b>나가면</b>그리는 사람이 나가면 그 라운드는 점수 없이 넘어가고, 남은 사람이 1명이면 게임이 끝납니다. 새로고침은 10초 안에 돌아오면 이어집니다</li>
          <li><b>중간 입장</b>게임 중에 들어오면 관전하고 다음 게임부터 참여합니다</li>
        </ul>
      </Card>
    </div>
    <PageNav title="캐치마인드" :items="sections" />
  </div>
</template>

<style scoped>
.host-tag { background: var(--accent); color: var(--accent-text); font-weight: 700; }
.err { margin: 0 0 10px; color: var(--danger); font-size: 1rem; }
tr.gone td { color: var(--muted); text-decoration: line-through; }
tr.mine td { background: var(--tag-bg); }
.statusbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 16px; margin-bottom: 12px; }
.headline { font-size: 1rem; }
.clock { padding: 2px 10px; border-radius: 999px; background: var(--tag-bg); font-variant-numeric: tabular-nums; font-weight: 700; }
.clock.low { background: var(--danger); color: var(--accent-text); }
.play { display: grid; grid-template-columns: minmax(0, 1fr) 280px; gap: 16px; align-items: stretch; }
.stage { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
.tools { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.swatch { width: 26px; height: 26px; padding: 0; border-radius: 50%; border: 2px solid var(--border); }
.swatch.on { outline: 2px solid var(--accent); outline-offset: 2px; }
.tools .on { border-color: var(--accent); color: var(--accent); }
.sep { width: 1px; height: 20px; background: var(--border); margin: 0 4px; }
.chatbox { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
.chatlog { list-style: none; margin: 0; padding: 10px; flex: 1; min-height: 200px; max-height: 420px; overflow-y: auto; background: var(--tag-bg); border-radius: 8px; font-size: 1rem; display: flex; flex-direction: column; gap: 4px; }
.chatlog li { overflow-wrap: anywhere; }
.chatlog li.k-system { color: var(--muted); }
.chatlog li.k-correct { color: var(--accent); font-weight: 600; }
.say { display: flex; gap: 6px; }
.say input { flex: 1; min-width: 0; }
.hint { margin: 0; }
.opt-note { align-self: center; font-size: 1rem; }
@media (max-width: 860px) { .play { grid-template-columns: minmax(0, 1fr); } }
</style>
