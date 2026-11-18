import { computed, ref, watch } from 'vue'
import type { Ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useWsStore } from '../stores/ws'
import type { WsEvent } from '../stores/ws'

/**
 * 실행 중 스피너 아래에 보여 줄 "최근 웹소켓 메시지".
 * - 이 실행이 시작된 뒤에 온 메시지만 돌려준다(이전 실행의 메시지가 남아 보이지 않게).
 * - methods 로 이 화면의 서비스가 보내는 메시지만 고른다. 웹소켓 패널은 적재, 이미지 캐시, RAG, 락 실험의 메시지를 한 목록에
 *   모으므로, 거르지 않으면 다른 서비스의 진행 메시지가 이 실행의 진행 상황처럼 보인다.
 */
export function useFreshLog(active: Ref<boolean>, methods: string[]) {
  const { messages } = storeToRefs(useWsStore())
  const base = ref<WsEvent | null>(null)
  const latest = () => messages.value.find((m) => methods.includes(m.method)) ?? null
  watch(active, (on) => {
    if (on) base.value = latest()
  })
  return computed(() => {
    const m = latest()
    return m !== base.value ? m : null
  })
}
