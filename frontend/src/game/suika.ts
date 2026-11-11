/** 수박 게임 엔진. 이미지 없이 원만 그린다. 화면(Vue)과 분리해 캔버스/물리만 다룬다. */
export const W = 360
export const H = 520
export const LINE_Y = 90 // 이 선 위로 공이 쌓이면 게임 오버
const GRAVITY = 0.35
const MAX_LEVEL = 10
const BEST_KEY = 'note.suika.best'

export const RADII = [16, 22, 29, 36, 44, 53, 63, 74, 86, 99, 113]
const SCORES = [1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66]
const BONUS = 100

interface Ball {
  x: number; y: number; vx: number; vy: number
  /** 회전 각도와 각속도(라디안, 프레임당) */
  angle: number; w: number
  level: number; age: number
  /** 합쳐져 막 생긴 공의 팝 효과 남은 프레임 */
  pop?: number
  dead?: boolean
}

/** 합칠 때 나오는 효과(링 / 불꽃 / 떠오르는 점수). */
interface Fx {
  kind: 'ring' | 'spark' | 'text'
  x: number; y: number; vx: number; vy: number
  life: number; max: number
  level: number
  r: number
  text?: string
}

export class Suika {
  balls: Ball[] = []
  score = 0
  best = 0
  over = false
  aimX = W / 2
  /** true면 벽·바닥·공끼리 탄성 있게 튕긴다(기본은 거의 안 튕김). */
  bouncy = false
  current = 0
  next = 0
  /** 이번 판에서 단계별로 만들어진 횟수 */
  made: number[] = new Array(MAX_LEVEL + 1).fill(0)
  drops = 0
  private fx: Fx[] = []
  private cooldown = 0
  private overTime = 0
  private raf = 0
  private last = 0
  private acc = 0

  constructor(private canvas: HTMLCanvasElement, private onChange: () => void) {
    const dpr = window.devicePixelRatio || 1
    canvas.width = W * dpr
    canvas.height = H * dpr
    canvas.getContext('2d')!.setTransform(dpr, 0, 0, dpr, 0, 0)
    this.best = loadBest()
    this.reset()
  }

  reset() {
    this.balls = []
    this.fx = []
    this.score = 0
    this.over = false
    this.overTime = 0
    this.cooldown = 0
    this.drops = 0
    this.made = new Array(MAX_LEVEL + 1).fill(0)
    this.current = this.randLevel()
    this.next = this.randLevel()
    this.clampAim() // 새 대기 공의 크기에 맞춰 벽 밖으로 나가지 않게
    this.onChange()
  }

  start() {
    this.stop() // 두 번 불려도 루프가 겹치지 않게 한다(HMR 등)
    this.acc = 0
    this.last = performance.now()
    const loop = (t: number) => {
      if (!this.canvas.offsetWidth) {
        // 카드가 접혀 판이 보이지 않으면 물리와 그리기를 건너뛴다(게임이 잠시 멈춘다).
        this.last = t; this.acc = 0
        this.raf = requestAnimationFrame(loop)
        return
      }
      this.acc += Math.min(t - this.last, 100)
      this.last = t
      while (this.acc >= 1000 / 60) { this.step(); this.acc -= 1000 / 60 }
      this.draw()
      this.raf = requestAnimationFrame(loop)
    }
    this.raf = requestAnimationFrame(loop)
  }

  stop() { cancelAnimationFrame(this.raf); this.raf = 0 }

  aim(clientX: number) {
    const r = this.canvas.getBoundingClientRect()
    if (r.width <= 0) return // 카드가 접혀 보이지 않으면 좌표를 계산할 수 없다(0으로 나누면 NaN)
    const x = ((clientX - r.left) / r.width) * W
    this.aimX = this.clamp(x)
  }

  private clamp(x: number) {
    const rad = RADII[this.current]
    return Math.max(rad, Math.min(W - rad, x))
  }

  private clampAim() { this.aimX = this.clamp(this.aimX) }

  drop() {
    if (this.over || this.cooldown > 0) return
    this.balls.push({ x: this.aimX, y: RADII[this.current], vx: 0, vy: 0, angle: 0, w: 0, level: this.current, age: 0 })
    this.drops++
    this.made[this.current]++ // 투하로 만든 공도 단계별 횟수에 센다
    this.current = this.next
    this.next = this.randLevel()
    this.cooldown = 30
    this.clampAim() // 바뀐 대기 공의 반지름에 맞춰 직접 보정한다(화면 좌표를 다시 거치지 않는다)
    this.onChange()
  }

  private randLevel() { return Math.floor(Math.random() * 5) }

  private step() {
    this.stepFx() // 게임이 끝나도 효과는 끝까지 재생한다
    if (this.over) return
    if (this.cooldown > 0) this.cooldown--
    for (const b of this.balls) {
      b.age++
      if (b.pop) b.pop--
      b.vy += GRAVITY
      b.vx *= 0.995
      b.x += b.vx
      b.y += b.vy
      b.w = Math.max(-0.5, Math.min(0.5, b.w * 0.995))
      b.angle += b.w
    }
    for (let it = 0; it < 4; it++) this.collide()
    this.merge()
    this.checkOver()
  }

  private collide() {
    const wallE = this.bouncy ? 0.55 : 0.2 // 벽·바닥 반발 계수
    const ballE = this.bouncy ? 0.6 : 0.1 // 공끼리 반발 계수
    for (const b of this.balls) {
      const r = RADII[b.level]
      // 벽·바닥과의 마찰: 접촉점의 미끄러짐(slip)을 줄이고, 줄인 속도(dv)만큼 회전(2·dv/r)을 만든다 → 벽·바닥에서도 구른다.
      if (b.x < r) {
        b.x = r
        if (b.vx < 0) b.vx *= -wallE // 벽으로 향하는 속도일 때만 반사한다
        const dv = (b.vy - b.w * r) * 0.15 // 왼쪽 벽 접촉점은 위로 향한다
        b.vy -= dv; b.w += (2 * dv) / r
      }
      if (b.x > W - r) {
        b.x = W - r
        if (b.vx > 0) b.vx *= -wallE
        const dv = (b.vy + b.w * r) * 0.15
        b.vy -= dv; b.w -= (2 * dv) / r
      }
      if (b.y > H - r) {
        b.y = H - r
        if (b.vy > 0) b.vy = b.vy < 1.5 ? 0 : b.vy * -wallE // 바닥으로 향할 때만 반사, 느리면 멈춰서 계속 떨지 않게
        const dv = (b.vx - b.w * r) / 6 // 미끄러짐을 절반으로 줄인다(3·dv = slip/2)
        b.vx -= dv; b.w += (2 * dv) / r
        b.vx *= 0.985; b.w *= 0.985 // 구름 저항
      }
    }
    const n = this.balls.length
    for (let i = 0; i < n; i++) {
      for (let j = i + 1; j < n; j++) {
        const a = this.balls[i], b = this.balls[j]
        const ra = RADII[a.level], rb = RADII[b.level]
        let dx = b.x - a.x, dy = b.y - a.y
        const d2 = dx * dx + dy * dy, min = ra + rb
        if (d2 >= min * min) continue
        if (Math.abs(dx) < 0.05) {
          // 정확히 정중앙 위에 얹힌 경우: 현실에서는 불안정 평형이라 굴러 떨어진다. 위쪽 공을 살짝 밀어 균형을 깬다.
          const up = a.y < b.y ? a : b
          up.x += Math.random() < 0.5 ? -0.5 : 0.5
          dx = b.x - a.x
        }
        const d = Math.sqrt(dx * dx + dy * dy) || 0.001
        dx /= d; dy /= d
        const ma = ra * ra, mb = rb * rb, tot = ma + mb
        const push = min - d
        a.x -= dx * push * (mb / tot); a.y -= dy * push * (mb / tot)
        b.x += dx * push * (ma / tot); b.y += dy * push * (ma / tot)
        const rv = (b.vx - a.vx) * dx + (b.vy - a.vy) * dy
        if (rv < 0) {
          const e = -rv < 1 ? 0 : ballE // 천천히 닿으면 튕기지 않고 얹힌다
          const imp = (-(1 + e) * rv) / (1 / ma + 1 / mb)
          a.vx -= (imp * dx) / ma; a.vy -= (imp * dy) / ma
          b.vx += (imp * dx) / mb; b.vy += (imp * dy) / mb

          // 접선 마찰: 접촉점에서 두 표면이 미끄러지는 속도(vt)를 줄이고, 그 힘으로 서로를 회전시킨다.
          const tx = -dy, ty = dx
          const vt = (b.vx - a.vx) * tx + (b.vy - a.vy) * ty - rb * b.w - ra * a.w
          const lim = 0.4 * imp // 쿨롱 마찰: 마찰력은 누르는 힘의 0.4배를 넘지 못한다
          const jt = Math.max(-lim, Math.min(lim, -vt / (3 * (1 / ma + 1 / mb))))
          a.vx -= (jt * tx) / ma; a.vy -= (jt * ty) / ma
          b.vx += (jt * tx) / mb; b.vy += (jt * ty) / mb
          a.w -= (2 * jt) / (ma * ra); b.w -= (2 * jt) / (mb * rb)
        }
      }
    }
    // 공끼리 밀어낸 결과로 벽·바닥 밖으로 나간 공을 마지막에 다시 안으로 넣는다(위치만 보정).
    for (const b of this.balls) {
      const r = RADII[b.level]
      if (b.x < r) { b.x = r; if (b.vx < 0) b.vx = 0 } // 안으로 파고드는 속도도 없애, 다음 단계에서 한 프레임 늦게 반응하지 않게 한다
      if (b.x > W - r) { b.x = W - r; if (b.vx > 0) b.vx = 0 }
      if (b.y > H - r) { b.y = H - r; if (b.vy > 0) b.vy = 0 }
    }
  }

  private merge() {
    const added: Ball[] = []
    const n = this.balls.length
    for (let i = 0; i < n; i++) {
      const a = this.balls[i]
      if (a.dead) continue
      for (let j = i + 1; j < n; j++) {
        const b = this.balls[j]
        if (b.dead || a.level !== b.level) continue
        const dx = b.x - a.x, dy = b.y - a.y, min = RADII[a.level] + RADII[b.level]
        if (dx * dx + dy * dy > (min + 0.5) ** 2) continue
        a.dead = b.dead = true
        const mx = (a.x + b.x) / 2, my = (a.y + b.y) / 2
        let points = SCORES[a.level]
        if (a.level < MAX_LEVEL) {
          added.push({
            x: mx, y: my, vx: (a.vx + b.vx) / 2, vy: (a.vy + b.vy) / 2,
            angle: a.angle, w: (a.w + b.w) / 2, level: a.level + 1, age: 0, pop: 12,
          })
          this.made[a.level + 1]++
          this.burst(mx, my, a.level + 1, `+${points}`)
        } else {
          points += BONUS // 최대 단계끼리 만나면 사라지며 보너스
          this.burst(mx, my, a.level, `+${points}`)
        }
        this.score += points
        break
      }
    }
    if (added.length || this.balls.some((b) => b.dead)) {
      this.balls = this.balls.filter((b) => !b.dead).concat(added)
      this.bumpBest()
      this.onChange()
    }
  }

  private checkOver() {
    const risky = this.balls.some((b) => b.age > 60 && b.y - RADII[b.level] < LINE_Y)
    this.overTime = risky ? this.overTime + 1 : 0
    if (this.overTime > 120) {
      this.over = true
      this.bumpBest()
      this.onChange()
    }
  }

  /** 최고 점수 갱신 시 로컬스토리지에도 저장한다. */
  private bumpBest() {
    if (this.score <= this.best) return
    this.best = this.score
    saveBest(this.best)
  }

  /** 합친 자리에 링 + 불꽃 + 점수를 띄운다. */
  private burst(x: number, y: number, level: number, text: string) {
    const r = RADII[level]
    this.fx.push({ kind: 'ring', x, y, vx: 0, vy: 0, life: 18, max: 18, level, r })
    for (let i = 0; i < 10; i++) {
      const ang = (Math.PI * 2 * i) / 10 + Math.random() * 0.4
      const sp = 1.5 + Math.random() * 2.5
      this.fx.push({ kind: 'spark', x, y, vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp, life: 26, max: 26, level, r: 2 + Math.random() * 2 })
    }
    this.fx.push({ kind: 'text', x, y: y - r * 0.3, vx: 0, vy: -0.7, life: 42, max: 42, level, r, text })
  }

  private stepFx() {
    for (const f of this.fx) {
      f.life--
      f.x += f.vx; f.y += f.vy
      if (f.kind === 'spark') { f.vy += 0.08; f.vx *= 0.97 }
    }
    if (this.fx.length) this.fx = this.fx.filter((f) => f.life > 0)
  }

  /** 프레임 시작에 한 번만 읽어 둔 단계별 색(공마다 getComputedStyle 을 부르지 않기 위함). */
  private palette: string[] = []
  private shine = ''

  private color(level: number) {
    return this.palette[level] || 'currentColor' // 토큰을 못 읽어도 직전 색이 남지 않게 한다
  }

  private draw() {
    const ctx = this.canvas.getContext('2d')!
    const css = getComputedStyle(this.canvas)
    const v = (k: string) => css.getPropertyValue(k).trim()
    this.palette = RADII.map((_, i) => v(`--fruit-${i + 1}`))
    this.shine = v('--ball-shine')
    ctx.clearRect(0, 0, W, H)
    ctx.fillStyle = v('--tag-bg')
    ctx.fillRect(0, 0, W, H)

    // 게임 오버 기준선
    ctx.strokeStyle = this.overTime > 0 ? v('--danger') : v('--border')
    ctx.setLineDash([6, 6])
    ctx.beginPath(); ctx.moveTo(0, LINE_Y); ctx.lineTo(W, LINE_Y); ctx.stroke()
    ctx.setLineDash([])

    // 조준 가이드 + 대기 공
    if (!this.over) {
      ctx.strokeStyle = v('--border')
      ctx.beginPath(); ctx.moveTo(this.aimX, 0); ctx.lineTo(this.aimX, H); ctx.stroke()
      this.circle(ctx, this.aimX, RADII[this.current] + 4, this.current, this.cooldown > 0 ? 0.35 : 1)
    }
    for (const b of this.balls) this.circle(ctx, b.x, b.y, b.level, 1, b.angle, 1 + 0.18 * ((b.pop ?? 0) / 12))
    this.drawFx(ctx, v('--text'))

    if (this.over) {
      ctx.fillStyle = v('--game-overlay')
      ctx.fillRect(0, 0, W, H)
      ctx.fillStyle = v('--game-overlay-text')
      ctx.textAlign = 'center'
      ctx.font = '700 32px system-ui, sans-serif'
      ctx.fillText('GAME OVER', W / 2, H / 2 - 6)
      ctx.font = '16px system-ui, sans-serif'
      ctx.fillText(`점수 ${this.score}`, W / 2, H / 2 + 22)
    }
  }

  private drawFx(ctx: CanvasRenderingContext2D, textColor: string) {
    ctx.save() // 글자 정렬·알파·선 굵기를 호출한 쪽에 남기지 않는다
    for (const f of this.fx) {
      const t = 1 - f.life / f.max // 0 → 1
      if (f.kind === 'ring') {
        ctx.globalAlpha = 1 - t
        ctx.strokeStyle = this.color(f.level)
        ctx.lineWidth = 4 * (1 - t) + 1
        ctx.beginPath(); ctx.arc(f.x, f.y, f.r * (0.7 + t * 0.9), 0, Math.PI * 2); ctx.stroke()
      } else if (f.kind === 'spark') {
        ctx.globalAlpha = 1 - t
        ctx.fillStyle = this.color(f.level)
        ctx.beginPath(); ctx.arc(f.x, f.y, f.r * (1 - t * 0.5), 0, Math.PI * 2); ctx.fill()
      } else {
        ctx.globalAlpha = Math.min(1, (1 - t) * 1.6)
        ctx.fillStyle = textColor
        ctx.textAlign = 'center'; ctx.textBaseline = 'middle'
        ctx.font = '700 15px system-ui, sans-serif'
        ctx.fillText(f.text ?? '', f.x, f.y)
      }
    }
    ctx.restore()
  }

  /** 원 그리기(색 + 공과 함께 도는 반투명 하이라이트). 판 밖의 아이콘과 같은 함수(drawBall)를 쓴다. */
  circle(ctx: CanvasRenderingContext2D, x: number, y: number, level: number, alpha: number, angle = 0, scale = 1) {
    drawBall(ctx, x, y, RADII[level] * scale, this.color(level), this.shine, angle, alpha)
  }
}

/** 공 한 개를 그린다. 게임 판과 오른쪽 패널의 아이콘(BallIcon)이 함께 쓰므로 모양이 항상 같다. */
export function drawBall(ctx: CanvasRenderingContext2D, x: number, y: number, r: number, color: string, shine: string, angle = 0, alpha = 1) {
  ctx.save() // 호출한 쪽의 alpha·fill 설정을 바꾸지 않는다
  ctx.globalAlpha = alpha
  ctx.fillStyle = color || 'currentColor' // 토큰을 못 읽었을 때 직전 색이 남지 않게 한다
  ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill()
  // 하이라이트가 공과 함께 돈다(별도 마커 없이 회전이 보인다). 기준 위치는 왼쪽 위.
  const a = angle + Math.atan2(-0.32, -0.3)
  const d = r * Math.hypot(0.3, 0.32)
  ctx.fillStyle = shine || 'transparent' // 색은 style.css 토큰(--ball-shine)에서 받고, 없으면 하이라이트를 생략한다
  ctx.beginPath(); ctx.arc(x + Math.cos(a) * d, y + Math.sin(a) * d, r * 0.28, 0, Math.PI * 2); ctx.fill()
  ctx.restore()
}

function loadBest(): number {
  try {
    const n = Number(localStorage.getItem(BEST_KEY))
    return Number.isFinite(n) && n > 0 ? n : 0
  } catch {
    return 0 // 저장소를 못 쓰는 환경(시크릿 모드 등)에서도 게임은 동작한다
  }
}

function saveBest(n: number) {
  try {
    // 다른 탭이 더 높은 점수를 저장했을 수 있으므로 저장된 값과 비교해 큰 쪽을 남긴다.
    const stored = Number(localStorage.getItem(BEST_KEY)) || 0
    localStorage.setItem(BEST_KEY, String(Math.max(stored, n)))
  } catch {
    /* 저장 실패는 무시 */
  }
}
