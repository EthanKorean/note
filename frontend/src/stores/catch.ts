import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { Client } from '@stomp/stompjs'

/** 백엔드 Messages 와 대응 */
export interface Draw { type: 'stroke' | 'clear' | 'snapshot'; sid: number; c: number; size: number; pts: number[][] | null }
export interface Chat { kind: 'chat' | 'system' | 'correct'; pid: number; nick: string; text: string }
export interface PlayerView {
  pid: number; nick: string; ready: boolean; score: number; online: boolean
  left: boolean; spectator: boolean; solved: boolean; host: boolean
}
export type Phase = 'LOBBY' | 'DRAWING' | 'REVEAL' | 'RESULT'
export interface State {
  seq: number; phase: Phase; win: number; laps: number; seconds: number; round: number; total: number
  drawerPid: number; wordLen: number; endsAt: number; serverNow: number
  revealWord: string | null; solverPid: number; players: PlayerView[]
}

export const PALETTE = 8 // 서버 GameRoom.PALETTE 와 같아야 한다(색 번호 0..7)
const MAX_CHAT = 200
const TOKEN_KEY = 'note.catch.token'
const NICK_KEY = 'note.catch.nick'

// 탭마다 다른 사람이어야 한 브라우저에서도 여러 명으로 테스트할 수 있어 sessionStorage 를 쓴다.
// (탭을 "복제"하면 sessionStorage 가 복사돼 같은 사람이 되니, 테스트할 때는 새 탭으로 연다.)
function load(key: string): string {
  try { return sessionStorage.getItem(key) ?? '' } catch { return '' }
}
function save(key: string, v: string) {
  try { v ? sessionStorage.setItem(key, v) : sessionStorage.removeItem(key) } catch { /* 저장 불가: 새로고침 복귀만 안 된다 */ }
}
function newToken(): string {
  const a = new Uint8Array(16)
  crypto.getRandomValues(a)
  return Array.from(a, (b) => b.toString(16).padStart(2, '0')).join('')
}

export const useCatchStore = defineStore('catch', () => {
  const connected = ref(false)
  const state = ref<State | null>(null)
  const chat = ref<Chat[]>([])
  const myPid = ref(0)
  const word = ref('')          // 내가 출제자일 때만 서버가 보내 준다
  const error = ref('')
  const nick = ref(load(NICK_KEY))
  /** 누적 채팅 수(목록이 200개로 잘려도 계속 늘어나, 화면이 새 메시지를 알아챈다). */
  const chatCount = ref(0)
  /** 서버 시계와 내 시계 차이를 없앤 "라운드 종료 시각"(내 시계 기준 ms). 0 이면 타이머 없음. */
  const deadline = ref(0)
  const strokes: Draw[] = []
  const drawListeners = new Set<(d: Draw) => void>()

  let client: Client | null = null
  let token = load(TOKEN_KEY)
  let lastSeq = 0   // 마지막으로 반영한 상태 번호(오래된 상태가 뒤늦게 오면 버린다)
  let gotState = false // 이번 연결에서 방 상태를 받았는지
  let gotMe = false // 이번 연결에서 입장 응답을 받았는지
  let joinSeq = 0 // 내 입장 응답이 알려 준 "이 번호보다 오래된 상태는 나를 모를 수 있다"

  const joined = computed(() => myPid.value > 0 && !!state.value?.players.some((p) => p.pid === myPid.value && !p.left))
  const me = computed(() => state.value?.players.find((p) => p.pid === myPid.value) ?? null)
  const isHost = computed(() => !!me.value?.host)
  const isDrawer = computed(() => !!state.value && state.value.phase === 'DRAWING' && state.value.drawerPid === myPid.value)

  function connect() {
    if (client) return
    if (!token) { token = newToken(); save(TOKEN_KEY, token) }
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    const c: Client = new Client({
      brokerURL: `${proto}://${location.host}/ws-catch?token=${token}`,
      reconnectDelay: 3000,
      onConnect: () => {
        if (client !== c) return // 이미 버린 옛 연결
        connected.value = true
        gotMe = false
        gotState = false
        joinSeq = 0 // 서버가 다시 시작했을 수 있으니 상태 번호 기준을 처음으로 돌린다(내 번호는 입장 응답(Me)이 새로 알려 준다)
        lastSeq = 0 // 서버가 다시 시작해 번호가 처음으로 돌아갔을 수 있다
        // 이미 버린 옛 연결의 뒤늦은 메시지가 새 연결의 상태를 건드리지 않게 한다.
        const sub = (dest: string, cb: (m: { body: string }) => void) => c.subscribe(dest, (m) => { if (client === c) cb(m) })
        sub('/user/queue/me', (m) => onMe(JSON.parse(m.body)))
        sub('/user/queue/strokes', (m) => {
          strokes.length = 0
          for (const d of JSON.parse(m.body).list as Draw[]) strokes.push(d)
          // 그림판이 서버 그림으로 다시 그린다(내가 아직 못 보낸 획은 그대로 남긴다).
          drawListeners.forEach((f) => f({ type: 'snapshot', sid: 0, c: 0, size: 0, pts: null }))
        })
        sub('/user/queue/state', (m) => onState(JSON.parse(m.body))) // 입장 전에도 방 상태를 보여 주려고 서버가 한 번 보내 준다
        sub('/topic/catch/state', (m) => onState(JSON.parse(m.body)))
        sub('/topic/catch/draw', (m) => onDraw(JSON.parse(m.body)))
        sub('/topic/catch/chat', (m) => {
          chat.value.push(JSON.parse(m.body))
          chatCount.value++
          if (chat.value.length > MAX_CHAT) chat.value.splice(0, chat.value.length - MAX_CHAT)
        })
        // 저장해 둔 닉네임으로 같은 자리에 복귀한다(새로고침·재연결).
        tryJoin(c, 0)
      },
      onWebSocketClose: () => { if (client === c) connected.value = false }, // 이미 버린 옛 연결의 뒤늦은 close 는 무시
    })
    client = c
    c.activate()
  }

  /**
   * 입장(복귀) 요청. 서버는 클라이언트 메시지를 한 스레드로 순서대로 처리하지만, 응답이 어떤 이유로든
   * 유실될 때를 대비한 안전장치로 응답이 올 때까지 짧게 다시 보낸다(입장은 여러 번 보내도 안전하다:
   * 이미 들어와 있으면 같은 자리로 응답만 다시 보낸다).
   */
  let joinChain = 0 // 재시도 체인 번호. 새 체인이 시작되면 이전 체인은 멈춘다(입장 요청이 겹쳐 나가지 않게)
  function tryJoin(c: Client, n: number, chain = ++joinChain) {
    if (chain !== joinChain || client !== c || !c.connected) return
    // 입장 응답(Me)과 방 상태를 모두 받을 때까지 다시 보낸다(둘 중 하나만 구독보다 먼저 나가 사라질 수 있다).
    if (nick.value) {
      if (gotMe && gotState) return
      pub('/app/catch/join', { nick: nick.value })
    } else {
      if (gotState) return
      pub('/app/catch/hello') // 아직 입장 전이면 방 상태와 그림만 받는다
    }
    // 처음엔 촘촘히, 그래도 안 되면 느리게 계속(연결이 살아 있는 동안 응답이 올 때까지). 연결이 끊기거나 새 체인이 시작되면 멈춘다.
    window.setTimeout(() => tryJoin(c, n + 1, chain), n < 4 ? 800 : 3000)
  }

  /** 화면을 떠난다. 서버는 GRACE 시간 안에 돌아오면 같은 사람으로 이어 주고, 아니면 퇴장 처리한다. */
  function disconnect() {
    client?.deactivate()
    client = null
    connected.value = false
    // 옛 방 화면이 남아 있다가 다시 들어올 때 입장한 것처럼 보이지 않게 비운다(닉네임은 남겨 자동 복귀).
    state.value = null
    myPid.value = 0
    word.value = ''
    deadline.value = 0
    error.value = ''
    chat.value = []
    strokes.length = 0
  }

  function onMe(m: { pid: number | null; error: string | null; word: string | null; seq: number }) {
    gotMe = true
    if (m.error) {
      error.value = m.error
      save(NICK_KEY, '')
      myPid.value = 0
      nick.value = '' // 같은 닉네임으로 재연결할 때마다 실패를 되풀이하지 않는다
      return
    }
    error.value = ''
    if (m.pid) { myPid.value = m.pid; joinSeq = m.seq }
    word.value = m.word ?? ''
  }

  function onState(s: State) {
    // 내가 목록에서 사라졌다(퇴장·강제 종료)면 입장 전 상태로 돌린다.
    // 내 입장보다 먼저 만들어진 상태가 뒤늦게 도착한 경우는 나를 모르는 게 당연하니 무시한다.
    if (s.seq < lastSeq) return
    lastSeq = s.seq
    gotState = true
    if (myPid.value && s.seq >= joinSeq && !s.players.some((p) => p.pid === myPid.value && !p.left)) {
      myPid.value = 0 // 닉네임은 남겨 둔다: 다시 연결되면 같은 닉네임으로 복귀를 시도한다
    }
    if (s.phase !== 'DRAWING') word.value = ''
    deadline.value = s.endsAt ? Date.now() + (s.endsAt - s.serverNow) : 0
    state.value = s
  }

  function onDraw(d: Draw) {
    if (d.type === 'clear') strokes.length = 0
    else strokes.push(d)
    drawListeners.forEach((f) => f(d))
  }

  /** 그림판이 구독한다. 반환값은 구독 해제 함수. */
  function onDrawEvent(f: (d: Draw) => void) {
    drawListeners.add(f)
    return () => drawListeners.delete(f)
  }
  const snapshot = () => [...strokes]

  /** 보냈으면 true. 연결이 끊긴 동안에는 보내지 못하므로 호출한 쪽이 입력을 지우지 않게 알려 준다. */
  function pub(dest: string, body: unknown = {}): boolean {
    if (!client?.connected) return false
    client.publish({ destination: dest, body: JSON.stringify(body) })
    return true
  }

  function join(n: string) {
    nick.value = n.trim()
    error.value = ''
    save(NICK_KEY, nick.value)
    gotMe = false
    if (client) tryJoin(client, 0)
  }
  function leave() {
    if (!pub('/app/catch/leave')) return // 연결이 끊겨 보내지 못했으면 그대로 둔다(서버에는 아직 남아 있다)
    save(NICK_KEY, '')
    nick.value = ''
    myPid.value = 0
    word.value = ''
  }
  const ready = (r: boolean) => pub('/app/catch/ready', { ready: r })
  const options = (laps: number, seconds: number): boolean => pub('/app/catch/options', { laps, seconds })
  const guess = (text: string): boolean => pub('/app/catch/guess', { text })
  const stroke = (sid: number, c: number, size: number, pts: number[][]): boolean => pub('/app/catch/stroke', { sid, c, size, pts })
  const clear = (): boolean => pub('/app/catch/clear')

  return {
    connected, state, chat, chatCount, myPid, word, error, nick, deadline, joined, me, isHost, isDrawer,
    connect, disconnect, join, leave, ready, options, guess, stroke, clear, onDrawEvent, snapshot,
  }
})
