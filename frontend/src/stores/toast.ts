import { ref } from 'vue'
import { defineStore } from 'pinia'
import { Client } from '@stomp/stompjs'

/** claude-toast 서버가 STOMP(/topic/toast)로 보내는 알림 (백엔드 Toast 레코드와 대응) */
export type ToastKind = 'done' | 'attention' | 'error' | 'info'

export interface ToastEvent {
  id: string
  kind: ToastKind
  title: string
  message: string
  project: string
  time: string
}

const KINDS: ToastKind[] = ['done', 'attention', 'error', 'info']
const MAX_SHOWN = 5
/** 자동으로 사라지는 시간(ms). 0 이면 직접 닫을 때까지 남는다(확인이 필요한 알림을 놓치지 않게). */
const AUTO_CLOSE_MS: Record<ToastKind, number> = { done: 10_000, info: 8_000, attention: 0, error: 0 }

type Permission = NotificationPermission | 'unsupported'

function parse(body: string): ToastEvent | null {
  try {
    const e = JSON.parse(body)
    if (typeof e?.id !== 'string' || !KINDS.includes(e.kind) || typeof e.title !== 'string') return null
    return { id: e.id, kind: e.kind, title: e.title, message: String(e.message ?? ''), project: String(e.project ?? ''), time: String(e.time ?? '') }
  } catch {
    return null
  }
}

export const useToastStore = defineStore('toast', () => {
  const toasts = ref<ToastEvent[]>([])
  const connected = ref(false)
  const permission = ref<Permission>(typeof Notification === 'undefined' ? 'unsupported' : Notification.permission)
  const timers = new Map<string, number>()
  /** 탭이 가려져 있어서 자동 닫힘 타이머를 아직 시작하지 못한 토스트 */
  const pending = new Set<string>()
  let client: Client | null = null

  function forget(id: string) {
    window.clearTimeout(timers.get(id))
    timers.delete(id)
    pending.delete(id)
  }

  function dismiss(id: string) {
    forget(id)
    toasts.value = toasts.value.filter((t) => t.id !== id)
  }

  /** 자동 닫힘은 탭이 보이는 동안에만 센다. 가려져 있으면(다른 스페이스 등) 아무도 못 본 채 사라지므로, 다시 보일 때 시작한다. */
  function startTimer(ev: ToastEvent) {
    const ms = AUTO_CLOSE_MS[ev.kind]
    if (ms <= 0) return
    if (document.hidden) pending.add(ev.id)
    else timers.set(ev.id, window.setTimeout(() => dismiss(ev.id), ms))
  }

  document.addEventListener('visibilitychange', () => {
    if (document.hidden) {
      // 보이는 동안 시작해 돌고 있던 타이머도 멈춘다(아니면 5초 보다가 스페이스를 옮긴 사이에 사라진다). 다시 보이면 처음부터 센다.
      for (const id of [...timers.keys()]) {
        forget(id)
        pending.add(id)
      }
      return
    }
    toasts.value.filter((t) => pending.delete(t.id)).forEach(startTimer)
  })

  function push(ev: ToastEvent) {
    // 같은 프로젝트의 같은 종류는 쌓지 않고 최신 것으로 바꾼다(확인 필요 알림이 계속 쌓이지 않게).
    toasts.value.filter((t) => t.kind === ev.kind && t.project === ev.project).forEach((t) => dismiss(t.id))
    const next = [ev, ...toasts.value]
    // 밀려나는 토스트의 자동 닫힘 타이머도 함께 치운다.
    next.slice(MAX_SHOWN).forEach((t) => forget(t.id))
    toasts.value = next.slice(0, MAX_SHOWN)
    startTimer(ev)
    // 다른 탭이나 다른 스페이스를 보고 있으면 화면의 토스트를 못 보므로 브라우저(OS) 알림도 띄운다.
    if (document.hidden && permission.value === 'granted') {
      try {
        const n = new Notification(ev.title, { body: ev.project ? `[${ev.project}] ${ev.message}` : ev.message, tag: ev.id })
        n.onclick = () => {
          window.focus()
          n.close()
        }
      } catch {
        // 알림을 만들 수 없는 환경이면 화면 토스트만 쓴다.
      }
    }
  }

  /** 앱을 열 때 한 번 연결한다. 서버가 꺼져 있으면 10초마다 다시 시도한다. */
  function connect() {
    if (client) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    client = new Client({
      brokerURL: `${proto}://${location.host}/ws-toast`,
      reconnectDelay: 10_000,
      onConnect: () => {
        connected.value = true
        client?.subscribe('/topic/toast', (m) => {
          const ev = parse(m.body)
          if (ev) push(ev)
        })
      },
      onWebSocketClose: () => (connected.value = false),
    })
    client.activate()
  }

  /** 브라우저 알림 권한은 사용자가 버튼을 눌렀을 때만 물을 수 있다. */
  async function requestPermission() {
    if (permission.value === 'unsupported') return
    permission.value = await Notification.requestPermission()
  }

  return { toasts, connected, permission, connect, dismiss, requestPermission }
})
