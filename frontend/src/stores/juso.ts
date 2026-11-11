import { ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/juso'
import { formatDateTime } from '../format'
import type { LoadParams, LoadResult, RunRecord, SavedResult, TableInfo } from '../types'

export const useJusoStore = defineStore('juso', () => {
  const runs = ref<RunRecord[]>([])
  const tables = ref<TableInfo[]>([])

  const running = ref(false)
  const tablesLoading = ref(false) // COUNT(*) 때문에 응답이 오래 걸릴 수 있다
  let tablesInflight = 0
  const queue = ref({ current: '', index: 0, total: 0 })
  const error = ref('')
  let seq = 0

  /** 서버에 저장된 과거 적재 기록을 불러온다(새로고침해도 유지). */
  async function loadRuns() {
    try {
      const saved = await api.fetchResults<LoadResult>('load')
      runs.value = saved.map(toRecord)
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  function toRecord(s: SavedResult<LoadResult>): RunRecord {
    return { ...s.result, id: `s${s.id}`, finishedAt: s.executedAt }
  }

  async function refreshTables() {
    tablesInflight++
    tablesLoading.value = true
    try {
      tables.value = await api.fetchTables()
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      if (--tablesInflight === 0) tablesLoading.value = false
    }
  }

  /** 선택한 방식을 순서대로 하나씩 실행한다(서버가 동시에 하나만 허용). 실패해도 나머지는 계속한다. */
  async function runLoadMany(methods: string[], paramsFor: (m: string) => LoadParams) {
    running.value = true
    error.value = ''
    const errors: string[] = []
    try {
      for (const [i, m] of methods.entries()) {
        queue.value = { current: m, index: i + 1, total: methods.length }
        const err = await runOne(m, paramsFor(m))
        if (err) errors.push(`${m}: ${err}`)
      }
    } finally {
      error.value = errors.join(' / ')
      running.value = false
      queue.value = { current: '', index: 0, total: 0 }
      await refreshTables()
    }
  }

  async function runOne(method: string, params: LoadParams): Promise<string> {
    try {
      const result = await api.startLoad(method, params)
      runs.value.unshift({
        ...result,
        id: `c${++seq}`,
        finishedAt: formatDateTime(new Date()),
      })
      return ''
    } catch (e) {
      return (e as Error).message
    }
  }

  async function truncate(engine: string) {
    error.value = ''
    try {
      await api.truncateTable(engine)
    } catch (e) {
      error.value = (e as Error).message
    }
    await refreshTables()
  }

  /** 저장된 적재 기록을 서버에서도 삭제한다. */
  async function clearRuns() {
    try {
      await api.clearResults('load')
      runs.value = []
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  return { runs, tables, tablesLoading, running, queue, error, loadRuns, refreshTables, runLoadMany, truncate, clearRuns }
})
