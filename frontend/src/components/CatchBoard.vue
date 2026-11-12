<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { PALETTE, useCatchStore, type Draw } from '../stores/catch'

/** 그림판. 좌표는 0~1 로 정규화해 보내므로 보는 화면 크기가 달라도 같은 그림이 된다. */
const props = defineProps<{ canDraw: boolean; color: number; size: number }>()

const store = useCatchStore()
const canvas = ref<HTMLCanvasElement>()
const REF_W = 600 // size 는 폭 600 기준 px 로 보내고, 실제 그릴 때 화면 폭에 맞춰 늘린다.
const FLUSH_MS = 80
const MIN_STEP = 0.003 // 이보다 가까운 점은 보내지 않는다(전송량·서버 한도 절약)

let ctx: CanvasRenderingContext2D | null = null
let unsub: (() => void) | null = null
let ro: ResizeObserver | null = null
let mq: MediaQueryList | null = null
let raf = 0

/** 획 번호별로 마지막으로 그린 점. 조각이 이어지는 획을 끊김 없이 잇는다. */
const last = new Map<number, number[]>()

/** 팔레트 색은 한 번만 읽어 두고(그릴 때마다 스타일을 조회하면 느리다), 라이트/다크가 바뀌면 다시 읽는다. */
let palette: string[] | null = null
function colorOf(c: number): string {
  if (!palette) {
    const st = getComputedStyle(canvas.value!)
    palette = Array.from({ length: PALETTE }, (_, i) => st.getPropertyValue(`--draw-${i + 1}`).trim() || 'currentColor')
  }
  return palette[c] ?? 'currentColor'
}

function drawChunk(d: Draw) {
  if (!ctx || !canvas.value || !d.pts?.length) return
  const w = canvas.value.width, h = canvas.value.height
  const scale = w / REF_W
  const erase = d.c < 0
  ctx.save()
  ctx.globalCompositeOperation = erase ? 'destination-out' : 'source-over'
  ctx.strokeStyle = erase ? 'currentColor' : colorOf(d.c) // 지우개는 색이 상관없다(destination-out)
  ctx.fillStyle = ctx.strokeStyle
  ctx.lineWidth = d.size * scale * (erase ? 2 : 1)
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  let prev = last.get(d.sid)
  for (const [x, y] of d.pts) {
    const px = x * w, py = y * h
    if (!prev) {
      ctx.beginPath()
      ctx.arc(px, py, ctx.lineWidth / 2, 0, Math.PI * 2) // 점 하나만 찍어도 보이게
      ctx.fill()
    } else {
      ctx.beginPath()
      ctx.moveTo(prev[0], prev[1])
      ctx.lineTo(px, py)
      ctx.stroke()
    }
    prev = [px, py]
  }
  ctx.restore()
  if (prev) last.set(d.sid, prev)
}

function clearAll() {
  if (!ctx || !canvas.value) return
  ctx.clearRect(0, 0, canvas.value.width, canvas.value.height)
  last.clear()
}

function redraw() {
  clearAll()
  store.snapshot().forEach(drawChunk)
  // 아직 서버에 못 보낸 내 획(전송 대기 조각, 쓰는 중인 점)은 서버 그림 위에 다시 얹는다.
  // 서버가 버려서 에코가 영영 안 오는 조각이 남지 않게, 5초가 지난 것은 더 얹지 않는다.
  inflight = inflight.filter((c) => Date.now() - c.at < 5000)
  inflight.forEach((ch) => drawChunk({ type: 'stroke', ...ch }))
  backlog.forEach((ch) => drawChunk({ type: 'stroke', ...ch }))
  if (curSid && buf.length) drawChunk({ type: 'stroke', sid: curSid, c: curColor, size: curSize, pts: buf })
}

function onTheme() {
  palette = null
  redraw()
}

function resize(): boolean {
  const c = canvas.value
  if (!c) return false
  const dpr = window.devicePixelRatio || 1
  const w = Math.round(c.clientWidth * dpr)
  const h = Math.round(c.clientHeight * dpr)
  if (!w || !h || (c.width === w && c.height === h)) return false
  c.width = w
  c.height = h
  redraw()
  return true
}

/** 내가 보낸 획 번호. 서버가 되돌려 주는 같은 조각은 이미 그렸으니 건너뛴다(재접속 때 받는 옛 그림은 그린다). */
const mine = new Set<number>()
/** 지우기 직전에 보낸 획의 에코가 지우기보다 늦게 도착해도 되살아나지 않게 막는 획 번호. */
const staleSids = new Set<number>()

/** 아직 못 보낸 점·조각과 재시도 타이머를 버린다(라운드가 바뀌거나 그림이 지워졌을 때 옛 선이 되살아나지 않게). */
function discardPending() {
  window.clearTimeout(timer)
  timer = 0
  buf = []
  backlog = []
  inflight = []
  lastSent = null
  curSid = 0
}

/** 내가 누른 "전체 지우기"는 바로 지우고, 서버가 되돌려 주는 같은 지우기는 한 번 건너뛴다(그 사이 새로 시작한 획을 지우지 않도록). */
let ownClears = 0 // 내가 보낸 뒤 아직 에코를 받지 못한 지우기 수
let ownClearAt = 0 // 마지막으로 보낸 시각. 에코가 영영 안 오는 경우(서버가 거절)에 다음 지우기를 삼키지 않도록 짧은 시간만 유효하다.
function clearMine() {
  if (!store.clear()) return
  clearAll()
  mine.forEach((s) => staleSids.add(s))
  mine.clear()
  discardPending()
  ownClears++
  ownClearAt = Date.now()
}
defineExpose({ clearMine })

function onRemote(d: Draw) {
  if (d.type === 'snapshot') {
    redraw() // 입장·재접속 때 받은 서버 그림으로 다시 그린다(못 보낸 내 획은 redraw 가 위에 얹는다)
    return
  }
  if (d.type === 'clear') {
    if (ownClears > 0 && Date.now() - ownClearAt < 3000) { ownClears--; return } // 내가 지운 것의 에코(여러 번 눌렀으면 그 수만큼)
    ownClears = 0
    inflight = [] // 남이 지웠다: 그 전에 보낸 조각은 더 얹지 않는다(내 지우기의 에코일 때는 그 뒤에 보낸 획을 지우면 안 된다)
    clearAll(); mine.clear(); staleSids.clear(); discardPending(); return
  }
  if (mine.has(d.sid) || staleSids.has(d.sid)) {
    const k = inflight.findIndex((c) => c.sid === d.sid) // 내 조각의 에코가 왔다: 이제 서버 그림에 들어 있다
    if (k >= 0) inflight.splice(k, 1)
    return
  }
  drawChunk(d)
}

// ---- 그리기(출제자) ----
let sid = Math.floor(Math.random() * 2_000_000_000) // 출제자마다 겹치지 않게 무작위로 시작한다(서버 int 범위 안)
let curSid = 0
let curPointer = -1 // 획을 그리고 있는 손가락/마우스. 다른 포인터의 이동·뗌은 무시한다
let curColor = 0 // 한 획 안에서는 시작할 때의 색·굵기를 유지한다(중간에 바꾸면 다음 획부터)
let curSize = 7
let buf: number[][] = []
let lastSent: number[] | null = null
let timer = 0

function norm(e: PointerEvent): number[] {
  const r = canvas.value!.getBoundingClientRect()
  const q = (v: number) => Math.round(Math.min(1, Math.max(0, v)) * 10000) / 10000 // 소수 넷째 자리(서버도 같은 정밀도): 메시지 크기를 줄인다
  return [q((e.clientX - r.left) / r.width), q((e.clientY - r.top) / r.height)]
}

/** 아직 서버에 보내지 못한 조각. 각 조각이 자기 획 번호·색·굵기를 지니므로, 연결이 끊긴 사이 획이 바뀌어도 섞이지 않는다. */
interface Chunk { sid: number; c: number; size: number; pts: number[][] }
let backlog: Chunk[] = []
/** 보냈지만 서버가 되돌려 주는 에코를 아직 못 받은 조각. 다시 그릴 때 이것도 얹어야 내 화면에서 빠지지 않는다. */
let inflight: (Chunk & { at: number })[] = []

function flush() {
  window.clearTimeout(timer)
  timer = 0
  // 1) 지금까지 모은 점을 조각으로 만든다(서버는 한 조각에 200점까지만 받는다). 앞 조각의 마지막 점에서 이어 그린다.
  while (buf.length) {
    const part = buf.slice(0, 150)
    backlog.push({ sid: curSid, c: curColor, size: curSize, pts: lastSent ? [lastSent, ...part] : part })
    lastSent = part[part.length - 1]
    buf = buf.slice(part.length)
  }
  if (backlog.length > 400) backlog = backlog.slice(-400) // 오래 끊겨 있으면 오래된 조각부터 버린다
  // 2) 순서대로 보낸다. 실패하면(연결 끊김) 남겨 두었다가 돌아올 때까지 다시 시도한다.
  while (backlog.length) {
    const ch = backlog[0]
    if (!store.stroke(ch.sid, ch.c, ch.size, ch.pts)) {
      timer = window.setTimeout(flush, 500)
      return
    }
    inflight.push({ ...backlog.shift()!, at: Date.now() })
  }
}

function onDown(e: PointerEvent) {
  if (!props.canDraw || e.button !== 0 || !e.isPrimary) return // 두 번째 손가락(멀티터치)은 무시한다
  if (curSid) flush() // 이전 획이 아직 열려 있으면 먼저 보낸다
  canvas.value!.setPointerCapture(e.pointerId)
  curSid = ++sid
  curPointer = e.pointerId
  curColor = props.color
  curSize = props.size
  mine.add(curSid)
  lastSent = null
  const p = norm(e)
  buf = [p]
  drawChunk({ type: 'stroke', sid: curSid, c: curColor, size: curSize, pts: [p] })
}

function onMove(e: PointerEvent) {
  if (!curSid || !props.canDraw || e.pointerId !== curPointer) return
  const p = norm(e)
  const prev = buf.length ? buf[buf.length - 1] : lastSent
  if (prev && Math.hypot(p[0] - prev[0], p[1] - prev[1]) < MIN_STEP) return
  buf.push(p)
  drawChunk({ type: 'stroke', sid: curSid, c: curColor, size: curSize, pts: [p] })
  if (buf.length >= 30) flush()
  else if (!timer) timer = window.setTimeout(flush, FLUSH_MS)
}

function onUp(e?: PointerEvent) {
  if (!curSid || (e && e.pointerId !== curPointer)) return
  flush()
  curSid = 0
}

// 출제 권한을 잃으면(라운드 종료) 쓰던 획을 닫는다.
watch(() => props.canDraw, (v) => {
  if (!v) { onUp(); discardPending() }
  ownClears = 0 // 역할이 바뀌면(라운드가 넘어가면) 지난 지우기의 에코를 더는 기다리지 않는다
  staleSids.clear()
})

onMounted(() => {
  ctx = canvas.value!.getContext('2d')
  if (!resize()) redraw() // 크기가 그대로면 resize 가 안 그리니 여기서 그린다
  unsub = store.onDrawEvent(onRemote)
  ro = new ResizeObserver(() => {
    if (!raf) raf = requestAnimationFrame(() => { raf = 0; resize() }) // 창 크기를 끄는 동안 프레임당 한 번만 다시 그린다
  })
  ro.observe(canvas.value!)
  mq = window.matchMedia('(prefers-color-scheme: dark)')
  mq.addEventListener('change', onTheme) // 라이트/다크가 바뀌면 색 토큰을 다시 읽어 그린다
})
onBeforeUnmount(() => {
  unsub?.()
  ro?.disconnect()
  mq?.removeEventListener('change', onTheme)
  window.clearTimeout(timer)
  if (raf) cancelAnimationFrame(raf)
})
</script>

<template>
  <canvas
    ref="canvas"
    class="catch-board"
    :class="{ drawable: canDraw }"
    aria-label="그림판"
    @pointerdown="onDown"
    @pointermove="onMove"
    @pointerup="onUp"
    @pointercancel="onUp"
  />
</template>

<style scoped>
.catch-board { display: block; width: 100%; aspect-ratio: 4 / 3; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; cursor: default; }
.catch-board.drawable { cursor: crosshair; touch-action: none; } /* 그리는 사람만 터치 스크롤을 막는다 */
</style>
