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
  let imageClient: Client | null = null

  const lastLog = computed(() => messages.value[0] ?? null)

  /** 이미지 캐시 서버의 메시지(적재 상태와는 별개라서 serverBusy 는 건드리지 않는다) */
  function handleImage(ev: WsEvent) {
    push(ev)
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

    imageClient = new Client({
      brokerURL: `${proto}://${location.host}/ws-image`,
      reconnectDelay: 3000,
      onConnect: () => imageClient?.subscribe('/topic/image', (m) => handleImage(JSON.parse(m.body) as WsEvent)),
    })
    imageClient.activate()
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

  return { connected, serverBusy, syncStatus, messages, unread, panelOpen, lastLog, connect, togglePanel, clear }
})
