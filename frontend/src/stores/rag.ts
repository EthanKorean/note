import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/rag'
import { clearResults, fetchResults } from '../api/juso'
import type { RagEvalParams, RagEvalResult, RagRun, RagSearchParams, RagSearchResult, RagStatus } from '../ragTypes'

export const useRagStore = defineStore('rag', () => {
  const status = ref<RagStatus | null>(null)
  const runs = ref<RagRun[]>([])
  const preparing = ref(false)
  const running = ref(false)
  const searching = ref(false)
  const searchResult = ref<RagSearchResult | null>(null)
  /** 질문 검색의 오류는 다른 카드(평가 실행)와 섞이지 않게 따로 둔다. */
  const searchError = ref('')
  /** 시나리오의 예시 질문을 "질문해 보기" 카드에 넘길 때 쓴다. 카드가 받아 가면 비운다. */
  const draftQuestion = ref('')
  const queue = ref({ current: '', index: 0, total: 0 })
  const error = ref('')
  /** 상태 조회(폴링)의 오류. 다른 작업의 오류(error)와 섞이지 않게 따로 둔다. */
  const statusError = ref('')
  /** 지금 도는 평가를 어느 카드가 시작했는지('eval' | 'scenarios' | ''). 시작한 카드에만 스피너를 띄운다. */
  const runOwner = ref('')
  let seq = 0

  /** 이 화면이 아니라 서버가 이미 작업 중(다른 탭, 새로고침 전에 시작한 평가 등). */
  const serverBusy = computed(() => !!status.value?.running && !preparing.value && !running.value && !searching.value)

  /** 실행, 검색, 평가 버튼을 막을 조건. 모든 카드가 같은 규칙을 쓴다. */
  const blocked = computed(() => running.value || preparing.value || searching.value || serverBusy.value || !status.value?.modelReady)

  async function refreshStatus() {
    try {
      status.value = await api.fetchRagStatus()
      statusError.value = ''
    } catch (e) {
      statusError.value = (e as Error).message
    }
  }

  async function loadRuns() {
    try {
      const saved = await fetchResults<RagEvalResult>('rag-eval')
      // 서버 목록과 이 화면의 실행을 합친다. 같은 실행(완료 시각과 조건이 같음)은 서버 쪽만 남기고, 서버 목록에 없는 실행은 지우지 않는다.
      // (서버에 저장하지 못했거나, 목록을 불러오는 사이에 방금 끝난 실행이 여기에 해당한다.)
      const key = (r: RagEvalResult) => `${r.finishedAt}|${r.chunker}|${r.size}|${r.overlap}`
      const fromServer = saved.map((s) => ({ ...s.result, id: `s${s.id}` }))
      const serverKeys = new Set(fromServer.map(key))
      const localOnly = runs.value.filter((r) => !serverKeys.has(key(r)))
      // 완료 시각이 최근인 순으로 놓는다(RagRuns 는 앞에 있는 것을 최신으로 본다).
      runs.value = [...localOnly, ...fromServer].sort((a, b) => b.finishedAt.localeCompare(a.finishedAt))
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  async function prepare() {
    preparing.value = true
    error.value = ''
    try {
      status.value = await api.prepareRag()
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      // 서버 상태를 먼저 갱신한 뒤에 내 실행 표시를 끈다. 반대로 하면 "서버가 다른 작업 중"이라는 낡은 값이 잠깐 보여 버튼이 잠긴다.
      await refreshStatus()
      preparing.value = false
    }
  }

  /** 청킹 조건을 순서대로 하나씩 평가한다. 실패해도 나머지는 계속한다. */
  async function runMany(list: RagEvalParams[], owner = '') {
    running.value = true
    runOwner.value = owner
    error.value = ''
    const errors: string[] = []
    try {
      for (const [i, p] of list.entries()) {
        queue.value = { current: p.chunker, index: i + 1, total: list.length }
        try {
          const r = await api.runRagEval(p)
          runs.value.unshift({ ...r, id: `c${++seq}` })
        } catch (e) {
          errors.push(`${p.chunker}: ${(e as Error).message}`)
        }
      }
    } finally {
      error.value = errors.join(' / ')
      await refreshStatus() // 먼저 서버 상태를 갱신하고 나서 내 실행 표시를 끈다(prepare 참고)
      running.value = false
      runOwner.value = ''
      queue.value = { current: '', index: 0, total: 0 }
    }
  }

  async function search(p: RagSearchParams) {
    searching.value = true
    searchError.value = ''
    searchResult.value = null // 이전 검색의 결과가 새 오류나 새 조건 옆에 남아 있지 않게 비운다
    try {
      searchResult.value = await api.searchRag(p)
    } catch (e) {
      searchError.value = (e as Error).message
    } finally {
      await refreshStatus()
      searching.value = false
    }
  }

  async function clearRuns() {
    try {
      await clearResults('rag-eval')
      runs.value = []
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  return { status, runs, preparing, running, searching, searchResult, searchError, draftQuestion, queue, error, statusError, runOwner, blocked, serverBusy, refreshStatus, loadRuns, prepare, runMany, search, clearRuns }
})
