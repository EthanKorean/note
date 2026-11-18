import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/lock'
import { clearResults, fetchResults } from '../api/juso'
import { isDefaultRun } from '../lockScenarios'
import type { LockParams, LockResult, LockRun, LockStatus } from '../lockTypes'

export const useLockStore = defineStore('lock', () => {
  const status = ref<LockStatus | null>(null)
  const runs = ref<LockRun[]>([])
  const running = ref(false)
  const queue = ref({ current: '', index: 0, total: 0 })
  const error = ref('')
  /** 상태 조회(폴링)의 오류. 실행 오류(error)와 섞이지 않게 따로 둔다. */
  const statusError = ref('')
  /** 지금 도는 실험을 어느 카드가 시작했는지('scenarios' | 'custom' | ''). 시작한 카드에만 스피너를 띄운다. */
  const runOwner = ref('')
  let seq = 0

  /** 이 화면이 아니라 서버가 이미 작업 중(다른 탭, 새로고침 전에 시작한 실험). */
  const serverBusy = computed(() => !!status.value?.running && !running.value)
  /** 실행 버튼을 막을 조건. 모든 카드가 같은 규칙을 쓴다. */
  const blocked = computed(() => running.value || serverBusy.value || !!status.value?.error || !!statusError.value || !status.value)

  async function refreshStatus() {
    try {
      status.value = await api.fetchLockStatus()
      statusError.value = ''
    } catch (e) {
      statusError.value = `lock-service 에 연결할 수 없습니다: ${(e as Error).message}`
    }
  }

  async function loadRuns() {
    const seqAtStart = seq // 목록을 불러오는 사이에 끝난 실행을 알아보려고 시작 시점을 기억해 둔다
    try {
      const saved = await fetchResults<LockResult>('lock-lab', 'success', 500) // 시나리오 표는 기본 조건의 최근 결과를 찾으므로 넉넉히 가져온다
      // 서버 목록과 이 화면의 실행을 합친다. 이 화면에서 돌린 실행(키 c…) 중 서버 목록에 없는 것은 두 경우만 남긴다:
      // 서버에 저장하지 못한 것, 목록을 불러오는 사이에 끝난 것. 그 밖에 서버 목록에서 사라진 실행(다른 탭에서 지움, 500건 한도)은 같이 지운다.
      const key = (r: LockResult) => `${r.finishedAt}|${r.id}`
      const fromServer = saved.map((s) => ({ ...s.result, key: `s${s.id}` }))
      const serverKeys = new Set(fromServer.map(key))
      const localOnly = runs.value.filter(
        (r) => r.key.startsWith('c') && !serverKeys.has(key(r)) && (!r.saved || Number(r.key.slice(1)) > seqAtStart),
      )
      runs.value = [...localOnly, ...fromServer].sort((a, b) => b.finishedAt.localeCompare(a.finishedAt))
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  /** 실험을 순서대로 하나씩 실행한다. 실패해도 나머지는 계속한다. */
  async function runMany(list: LockParams[], owner = '') {
    running.value = true
    runOwner.value = owner
    error.value = ''
    const errors: string[] = []
    try {
      for (const [i, p] of list.entries()) {
        queue.value = { current: p.id, index: i + 1, total: list.length }
        try {
          const r = await api.runLock(p)
          runs.value.unshift({ ...r, key: `c${++seq}` })
        } catch (e) {
          errors.push(`${p.id}: ${(e as Error).message}`)
        }
      }
    } finally {
      error.value = errors.join(' / ')
      await refreshStatus() // 서버 상태를 먼저 갱신하고 나서 내 실행 표시를 끈다(반대로 하면 낡은 "서버가 작업 중" 값이 잠깐 보인다)
      running.value = false
      runOwner.value = ''
      queue.value = { current: '', index: 0, total: 0 }
    }
  }

  async function clearRuns() {
    try {
      await clearResults('lock-lab')
      runs.value = []
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  /** 실험별 가장 최근 결과(runs 는 최근 실행이 앞). 시나리오 표용이라 기본 조건으로 돌린 것만 쓴다. */
  const latest = computed(() => {
    const m = new Map<string, LockRun>()
    for (const r of runs.value) if (isDefaultRun(r) && !m.has(r.id)) m.set(r.id, r)
    return m
  })

  return { status, runs, running, queue, error, statusError, runOwner, serverBusy, blocked, latest, refreshStatus, loadRuns, runMany, clearRuns }
})
