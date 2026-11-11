/** 밀리초 → "12.3초" / "2분 05초" */
export function formatDuration(ms: number): string {
  if (ms < 1000) return `${ms}ms`
  const sec = ms / 1000
  if (sec < 9.995) return `${sec.toFixed(2)}초`
  if (sec < 59.95) return `${sec.toFixed(1)}초`
  const total = Math.round(sec)
  const m = Math.floor(total / 60)
  const s = total % 60
  return `${m}분 ${String(s).padStart(2, '0')}초`
}

export const formatNumber = (n: number) => Math.round(n).toLocaleString('ko-KR')

export const formatMb = (bytes: number) => `${(bytes / 1_000_000).toFixed(1)} MB`

/** Date → "2026-11-10 09:46:27" */
export function formatDateTime(d: Date): string {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 완료 시각 "YYYY-MM-DD HH:mm:ss"에서 총 소요를 빼 시작 시각을 구한다(초 단위 근사). */
export function startedAt(finishedAt: string, totalMs: number): string {
  const end = new Date(finishedAt.replace(' ', 'T'))
  if (Number.isNaN(end.getTime())) return '-'
  return formatDateTime(new Date(end.getTime() - totalMs))
}
