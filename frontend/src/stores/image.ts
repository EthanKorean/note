import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import * as api from '../api/image'
import { clearResults, fetchResults } from '../api/juso'
import type { ImageBenchParams, ImageBenchResult, ImageRun, ImageStatus, ReplaceResult, ReplaceRun, ReplaceStrategy } from '../types'

export const useImageStore = defineStore('image', () => {
  const status = ref<ImageStatus | null>(null)
  const runs = ref<ImageRun[]>([])
  const running = ref(false)
  const seeding = ref(false)
  const replaceRuns = ref<ReplaceRun[]>([])
  const replacing = ref(false)
  const seedSource = ref<'draw' | 'web'>('draw')
  const queue = ref({ current: '', index: 0, total: 0 })
  const error = ref('')
  let seq = 0
  /** 이 화면이 아니라 서버가 이미 작업 중(다른 탭, 새로고침 전에 시작한 실험 등). */
  const serverBusy = computed(() => !!status.value?.running && !running.value && !seeding.value && !replacing.value)

  async function refreshStatus() {
    try {
      status.value = await api.fetchImageStatus()
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  async function loadRuns() {
    try {
      const saved = await fetchResults<ImageBenchResult>('image-cache')
      runs.value = saved.map((s) => ({ ...s.result, id: `s${s.id}` }))
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  async function seed(count: number, force = false, source: 'draw' | 'web' = 'draw') {
    seedSource.value = source
    seeding.value = true
    error.value = ''
    try {
      await api.seedImages(count, force, source)
    } catch (e) {
      error.value = (e as Error).message
    } finally {
      seeding.value = false
      await refreshStatus()
    }
  }

  /** 선택한 방식을 순서대로 하나씩 실행한다. 실패해도 나머지는 계속한다. */
  async function runMany(paramsList: ImageBenchParams[]) {
    running.value = true
    error.value = ''
    const errors: string[] = []
    try {
      for (const [i, p] of paramsList.entries()) {
        queue.value = { current: p.mode, index: i + 1, total: paramsList.length }
        try {
          const r = await api.runBench(p)
          runs.value.unshift({ ...r, id: `c${++seq}` })
        } catch (e) {
          errors.push(`${p.mode}: ${(e as Error).message}`)
        }
      }
    } finally {
      error.value = errors.join(' / ')
      running.value = false
      void refreshStatus()
      queue.value = { current: '', index: 0, total: 0 }
    }
  }

  async function loadReplaceRuns() {
    try {
      const saved = await fetchResults<ReplaceResult>('image-replace')
      replaceRuns.value = saved.map((x) => ({ ...x.result, id: `s${x.id}` }))
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  /** 선택한 무효화 방식을 순서대로 실행한다. */
  async function runReplaceMany(strategies: ReplaceStrategy[], items: number, ttlSec: number) {
    replacing.value = true
    error.value = ''
    const errors: string[] = []
    try {
      for (const st of strategies) {
        try {
          const r = await api.runReplace(st, items, ttlSec)
          replaceRuns.value.unshift({ ...r, id: `r${++seq}` })
        } catch (e) {
          errors.push(`${st}: ${(e as Error).message}`)
        }
      }
    } finally {
      error.value = errors.join(' / ')
      replacing.value = false
      void refreshStatus()
    }
  }

  async function clearReplaceRuns() {
    try {
      await clearResults('image-replace')
      replaceRuns.value = []
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  async function clearRuns() {
    try {
      await clearResults('image-cache')
      runs.value = []
    } catch (e) {
      error.value = (e as Error).message
    }
  }

  return { status, runs, running, seeding, seedSource, serverBusy, replaceRuns, replacing, loadReplaceRuns, runReplaceMany, clearReplaceRuns, queue, error, refreshStatus, loadRuns, seed, runMany, clearRuns }
})
