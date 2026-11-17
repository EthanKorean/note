import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { Client } from '@stomp/stompjs'
import { fetchStatus } from '../api/juso'

/** 서버가 STOMP(/topic/juso)로 보내는 메시지 (백엔드 LoadEvents.Event 와 대응) */
export interface WsEvent {
  level: 'info' | 'success' | 'error'
  method: string
  message: string
  time: string
}

const MAX_MESSAGES = 200

export const useWsStore = defineStore('ws', () => {
  const connected = ref(false)
  const messages = ref<WsEvent[]>([])
  const unread = ref(0)
  const panelOpen = ref(false)
  /** 서버에서 적재가 진행 중인지(이 화면에서 시작하지 않은 실행 포함) */
  const serverBusy = ref(false)
  let client: Client | null = null

  const lastLog = computed(() => messages.value[0] ?? null)

  /** 적재(juso) 외 서비스(이미지 캐시, RAG)의 메시지를 같은 패널로 모은다. 적재 상태 serverBusy 는 건드리지 않는다. */
  function openTopic(brokerURL: string, topic: string): void {
    const c: Client = new Client({
      brokerURL,
      reconnectDelay: 3000,
      onConnect: () => c.subscribe(topic, (m) => push(JSON.parse(m.body) as WsEvent)),
    })
    c.activate()
  }

  function handle(ev: WsEvent) {
    if (ev.level === 'info') serverBusy.value = true
    else serverBusy.value = false // success | error = 끝남
    push(ev)
  }

  function push(ev: WsEvent) {
    messages.value.unshift(ev)
    if (messages.value.length > MAX_MESSAGES) messages.value.length = MAX_MESSAGES
    if (!panelOpen.value) unread.value++
  }

  function connect() {
    if (client) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    client = new Client({
      brokerURL: `${proto}://${location.host}/ws-juso`,
      reconnectDelay: 3000,
      onConnect: () => {
        connected.value = true
        syncStatus()
        client?.subscribe('/topic/juso', (m) => handle(JSON.parse(m.body) as WsEvent))
      },
      onWebSocketClose: () => (connected.value = false),
    })
    client.activate()

  }

  /**
   * 이미지 캐시, RAG 서비스의 메시지는 그 화면을 처음 열 때 연결한다. 모든 화면에서 미리 열어 두면 해당 서비스가 꺼져 있는 동안
   * 3초마다 연결에 실패하는 요청이 탭이 열려 있는 내내 반복된다.
   */
  const topics = {
    image: { path: '/ws-image', topic: '/topic/image' },
    rag: { path: '/ws-rag', topic: '/topic/rag' },
  } as const
  const opened = new Set<string>()
  function connectTopic(name: keyof typeof topics) {
    if (opened.has(name)) return
    opened.add(name)
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    openTopic(`${proto}://${location.host}${topics[name].path}`, topics[name].topic)
  }

  /** 새로 열었거나 재연결했을 때 서버의 현재 상태로 맞춘다. 상태 API가 없으면 웹소켓 메시지만 따른다. */
  async function syncStatus() {
    try {
      serverBusy.value = (await fetchStatus()).running
    } catch {
      // 무시
    }
  }

  // 실행 중 서버가 재시작되면 종료 메시지를 못 받아 계속 '실행 중'으로 남을 수 있어, 실행 중에는 주기적으로 맞춘다.
  setInterval(() => {
    if (serverBusy.value && connected.value) syncStatus()
  }, 10_000)

  function togglePanel() {
    panelOpen.value = !panelOpen.value
    if (panelOpen.value) unread.value = 0
  }

  function clear() {
    messages.value = []
    unread.value = 0
  }

  return { connected, serverBusy, syncStatus, messages, unread, panelOpen, lastLog, connect, connectTopic, togglePanel, clear }
})
