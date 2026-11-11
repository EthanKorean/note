/**
 * 동작 원리·조작 규칙 항목별 설명과 소스. suika.ts 의 실제 코드를 화면용으로 줄인 것이다(로직을 바꾸면 여기도 고친다).
 * notes 의 순서가 화면에서 ①②③… 번호가 되고, 코드 주석의 번호와 짝을 이룬다.
 */
import type { CodeBlock } from '../methodSamples'

export interface Step {
  key: string
  /** 목록에 보이는 한 줄 설명 */
  summary: string
  /** 펼쳤을 때 보이는 구현 포인트(자동으로 ①②③ 번호가 붙는다) */
  notes: string[]
  blocks: CodeBlock[]
}

const ts = (title: string, code: string): CodeBlock => ({ lang: 'typescript', title, code })

export const steps: Step[] = [
  {
    key: '그리기',
    summary: '<canvas>에 원(arc)만 그린다. 이미지 파일 없이 색 토큰(--fruit-N)과 하이라이트 원 하나로 구분한다.',
    notes: [
      '색은 CSS 변수(--fruit-1~11, --ball-shine)를 프레임 시작에 한 번 읽어 두고 쓴다. 라이트/다크 테마가 자동으로 따라오고, 코드에 색 값을 직접 쓰지 않는다. 토큰을 못 읽으면 공과 효과는 글자색으로, 하이라이트는 생략해 직전 색이 남지 않게 한다.',
      '원은 arc()로 채운다. 단계마다 반지름(RADII)이 다르다.',
      '반투명 흰 원을 왼쪽 위로 비껴 하나 더 그려 입체감을 낸다. 이 원은 공의 각도만큼 같이 돌아서 구르는 것이 보인다.',
      '화면 배율(devicePixelRatio)만큼 캔버스 해상도를 키우고 좌표계는 360×520 그대로 쓴다.',
    ],
    blocks: [
      ts('game/suika.ts · draw() / drawBall()', `// draw(): 프레임 시작에 토큰을 한 번만 읽는다
this.palette = RADII.map((_, i) => v(\`--fruit-\${i + 1}\`))   // 1) --fruit-N
this.shine = v('--ball-shine')                                  // 1) 하이라이트 색 토큰

// circle() 은 drawBall() 에 위임한다(판 밖 아이콘 BallIcon 도 같은 함수를 쓴다)
export function drawBall(ctx, x, y, r, color, shine, angle = 0, alpha = 1) {
  ctx.save()
  ctx.globalAlpha = alpha
  ctx.fillStyle = color || 'currentColor'                           // (토큰을 못 읽으면 글자색)
  ctx.beginPath(); ctx.arc(x, y, r, 0, Math.PI * 2); ctx.fill()    // 2) 원
  ctx.fillStyle = shine || 'transparent'                            // 3) 하이라이트(왼쪽 위로 비껴서, 각도만큼 회전)
  ctx.beginPath(); ctx.arc(x + Math.cos(a) * d, y + Math.sin(a) * d, r * 0.28, 0, Math.PI * 2); ctx.fill()   // a, d: 기준 위치 + 공의 각도
  ctx.restore()
}`),
      ts('game/suika.ts · constructor', `const dpr = window.devicePixelRatio || 1
canvas.width = W * dpr                    // 4) 실제 해상도
canvas.height = H * dpr
ctx.setTransform(dpr, 0, 0, dpr, 0, 0)    // 4) 좌표는 W×H 기준 유지`),
    ],
  },
  {
    key: '루프',
    summary: 'requestAnimationFrame으로 매 프레임 그리고, 물리는 1/60초 고정 간격으로 계산해 기기 속도와 무관하게 같게 움직인다.',
    notes: [
      '프레임 간 경과 시간을 acc에 누적한다. 탭을 오래 비웠다 돌아와도 한 번에 쏟아지지 않도록 100ms로 제한한다.',
      '1/60초가 찰 때마다 물리를 한 번씩 진행한다. 모니터가 144Hz여도, 프레임이 끊겨도 게임 속도는 같다.',
      '물리를 마친 뒤 한 번 그리고 다음 프레임을 예약한다.',
      '카드가 접혀 판이 보이지 않으면(폭 0) 물리와 그리기를 건너뛰어 게임이 잠시 멈추고, 다시 펼치면 이어서 진행한다.',
    ],
    blocks: [
      ts('game/suika.ts · start()', `const loop = (t: number) => {
  if (!this.canvas.offsetWidth) {            // 4) 접혀서 안 보이면 건너뜀
    this.last = t; this.acc = 0
    this.raf = requestAnimationFrame(loop)
    return
  }
  this.acc += Math.min(t - this.last, 100)   // 1) 경과 시간 누적(최대 100ms)
  this.last = t
  while (this.acc >= 1000 / 60) {            // 2) 고정 간격으로 따라잡기
    this.step()
    this.acc -= 1000 / 60
  }
  this.draw()                                // 3) 그리고
  this.raf = requestAnimationFrame(loop)     // 3) 다음 프레임 예약
}`),
    ],
  },
  {
    key: '이동',
    summary: '매 단계 속도에 중력을 더하고 위치에 반영한다. 벽과 바닥에 닿으면 위치를 안쪽으로 되돌리고 속도를 줄인다.',
    notes: [
      '세로 속도에 중력(0.35)을 더한다.',
      '가로 속도는 프레임마다 0.5%씩 줄어든다(공기 저항).',
      '위치에 속도를 더해 이동한다.',
      '좌우 벽은 위치를 경계로 되돌리고, 벽으로 향하는 속도일 때만 반대로 뒤집되 20%만 남긴다(튕기기 옵션을 켜면 55%). 벽에서 멀어지는 속도는 건드리지 않는다.',
      '바닥도 같은 방식이고, 세로 속도가 1.5보다 작으면 0으로 멈춰 계속 떨지 않게 한다. 벽·바닥의 마찰은 아래 "회전"에서 다룬다.',
      '공끼리 밀어낸 결과로 벽·바닥 밖에 나간 공은, 충돌 계산 맨 끝에서 위치를 다시 판 안으로 넣고 안쪽으로 향하던 속도도 0으로 만든다(다음 단계에서 한 프레임 늦게 반응하지 않게).',
    ],
    blocks: [
      ts('game/suika.ts · step()', `for (const b of this.balls) {
  b.age++
  b.vy += GRAVITY      // 1) 중력 0.35
  b.vx *= 0.995        // 2) 공기 저항
  b.x += b.vx          // 3) 이동
  b.y += b.vy
}`),
      ts('game/suika.ts · collide() 벽·바닥', `const wallE = this.bouncy ? 0.55 : 0.2        // 4) 반발 계수(옵션)
const r = RADII[b.level]
if (b.x < r) {
  b.x = r
  if (b.vx < 0) b.vx *= -wallE                    // 4) 벽으로 향할 때만 반사(왼쪽)
}
if (b.x > W - r) {
  b.x = W - r
  if (b.vx > 0) b.vx *= -wallE                    // 4) 오른쪽 벽
}
if (b.y > H - r) {
  b.y = H - r
  if (b.vy > 0) b.vy = b.vy < 1.5 ? 0 : b.vy * -wallE   // 5) 바닥: 향할 때만 반사, 느리면 멈춤
}
// (마찰·회전은 "회전" 항목)

// collide() 맨 끝, 공끼리 밀어낸 뒤
if (b.x < r)     { b.x = r;     if (b.vx < 0) b.vx = 0 }   // 6) 밀려난 공을 다시 판 안으로
if (b.x > W - r) { b.x = W - r; if (b.vx > 0) b.vx = 0 }   // 6) 안으로 파고드는 속도도 없앤다
if (b.y > H - r) { b.y = H - r; if (b.vy > 0) b.vy = 0 }`),
    ],
  },
  {
    key: '충돌',
    summary: '두 원의 중심 거리가 반지름 합보다 작으면 겹친 것이다. 질량(반지름²)에 비례해 서로 밀어내고, 맞닿는 방향의 속도를 충격량으로 보정한다.',
    notes: [
      '겹침 판정은 제곱 값으로 먼저 비교해 겹치지 않는 쌍은 제곱근 계산을 건너뛴다.',
      '충돌 방향(단위 벡터)을 구하고, 질량은 반지름²로 둔다.',
      '위치 보정: 겹친 만큼을 질량 반비례로 나눠 서로 반대 방향으로 민다. 큰 원은 덜 밀린다.',
      '속도 보정: 서로 다가오는 중일 때만 충격량을 계산해 두 원에 질량 반비례로 나눈다. 반발 계수는 0.1(튕기기 옵션을 켜면 0.6)이고, 천천히 닿으면 0으로 두어 얹힌다.',
      '한계: 쌍 비교가 O(n²)이다. 공이 수십 개인 이 판에서는 문제가 없다. 접선 마찰과 회전은 "회전" 항목에서 다룬다.',
      '정확히 정중앙 위에 얹히면(x 차이가 0에 가까우면) 힘이 수직이라 영원히 균형을 잡는다. 현실에서는 불안정 평형이라 굴러 떨어지므로 위쪽 공을 0.5px 밀어 균형을 깬다.',
    ],
    blocks: [
      ts('game/suika.ts · collide() 원끼리', `for (let i = 0; i < n; i++) for (let j = i + 1; j < n; j++) {   // 5) 모든 쌍 O(n²)
  const a = this.balls[i], b = this.balls[j]
  let dx = b.x - a.x, dy = b.y - a.y
  const min = RADII[a.level] + RADII[b.level]
  if (dx * dx + dy * dy >= min * min) continue   // 1) 안 겹침(제곱 비교)

  if (Math.abs(dx) < 0.05) {                     // 6) 정중앙 위에 얹힘 → 균형 깨기
    const up = a.y < b.y ? a : b
    up.x += Math.random() < 0.5 ? -0.5 : 0.5
    dx = b.x - a.x
  }
  const d = Math.sqrt(dx * dx + dy * dy) || 0.001
  dx /= d; dy /= d                               // 2) 충돌 방향(단위 벡터)
  const ma = RADII[a.level] ** 2, mb = RADII[b.level] ** 2   // 2) 질량 = 반지름²

  // 3) 위치 보정: 겹친 만큼 질량 반비례로 밀어낸다
  const push = min - d
  a.x -= dx * push * (mb / (ma + mb)); a.y -= dy * push * (mb / (ma + mb))
  b.x += dx * push * (ma / (ma + mb)); b.y += dy * push * (ma / (ma + mb))

  // 4) 속도 보정: 접근 중일 때만 충격량
  const rv = (b.vx - a.vx) * dx + (b.vy - a.vy) * dy
  if (rv < 0) {
    const e = -rv < 1 ? 0 : (this.bouncy ? 0.6 : 0.1)   // 4) 반발 계수
    const imp = (-(1 + e) * rv) / (1 / ma + 1 / mb)
    a.vx -= (imp * dx) / ma; a.vy -= (imp * dy) / ma
    b.vx += (imp * dx) / mb; b.vy += (imp * dy) / mb
    // (이어서 접선 마찰·회전 → "회전" 항목)
  }
}`),
    ],
  },
  {
    key: '안정화',
    summary: '한 프레임에 충돌 처리를 4번 반복해 쌓인 공이 떨리거나 서로 파고드는 것을 줄인다.',
    notes: [
      '충돌 보정을 한 프레임에 4번 반복한다.',
      '한 번만 처리하면 아래 공이 위 공에 눌려 서로 파고들거나 떨린다. 반복할수록 밀어낸 결과가 이웃에게 전파되어 안정된다.',
      '대신 횟수만큼 계산량이 늘어난다(충돌 계산 4배).',
    ],
    blocks: [ts('game/suika.ts · step()', `for (let it = 0; it < 4; it++) this.collide()   // 1) 4회 반복 · 2) 파고듦 방지 · 3) 계산량 4배
this.merge()
this.checkOver()`)],
  },
  {
    key: '합치기',
    summary: '같은 단계 두 원이 닿으면 둘을 지우고 중간 위치에 한 단계 큰 원을 만든다. 속도는 둘의 평균, 점수는 단계가 오를수록 커진다.',
    notes: [
      '충돌 처리가 끝난 뒤 따로 돈다. 밀어냄은 같은 단계끼리도 똑같이 적용되고, 합치기는 그 뒤에 판정한다.',
      '같은 단계이고 거리가 반지름 합 + 0.5 이하이면 닿은 것으로 본다.',
      '두 원을 지우고 중간 위치에 한 단계 큰 원을 만든다. 속도는 둘의 평균이다.',
      '점수는 합치기 전 단계의 값을 더한다.',
      '가장 큰 원 둘이 만나면 새 원 없이 사라지고 +100점 보너스.',
    ],
    blocks: [
      ts('game/suika.ts · step() / merge()', `this.merge()                        // 1) 충돌 보정(4회)이 끝난 뒤에 호출

// merge() 안
if (a.level === b.level && dist <= RADII[a.level] + RADII[b.level] + 0.5) {   // 2) 같은 단계 + 닿음
  a.dead = b.dead = true                                // 3) 둘 다 지움
  this.score += SCORES[a.level]                         // 4) 합치기 전 단계의 점수
  if (a.level < MAX_LEVEL) {
    added.push({
      x: (a.x + b.x) / 2, y: (a.y + b.y) / 2,           // 3) 중간 위치
      vx: (a.vx + b.vx) / 2, vy: (a.vy + b.vy) / 2,      // 3) 평균 속도
      angle: a.angle, w: (a.w + b.w) / 2,                // 3) 회전도 평균
      level: a.level + 1, age: 0, pop: 12,               // (pop: 새 공이 커졌다 줄어드는 효과)
    })
  } else {
    this.score += 100                                   // 5) 최대 단계끼리는 사라지며 보너스
  }
}`),
    ],
  },
  {
    key: '회전',
    summary: '공마다 각도와 각속도를 가진다. 접촉점의 마찰이 공을 돌려서 경사에서 미끄러지지 않고 구른다.',
    notes: [
      '공은 각도(angle)와 각속도(w)를 가지고, 매 단계 angle += w. 각속도는 0.5%씩 줄고 ±0.5로 제한한다.',
      '접촉점에서 두 표면이 미끄러지는 속도(vt)를 구한다. 이동 속도 차이에서 회전 속도(r·w)를 뺀 값이다.',
      '마찰 충격량 jt는 vt를 없애는 값이되, 누르는 힘(imp)의 0.4배를 넘지 못한다(쿨롱 마찰).',
      'jt는 접선 방향으로 속도를 바꾸고, 같은 힘이 원을 돌린다. 원판의 관성모멘트(½mr²) 때문에 회전 변화량은 2·jt/(m·r)이다.',
      '벽과 바닥도 같은 방식이다. 미끄러짐을 줄인 속도(dv)만큼 회전(2·dv/r)을 만들고, 바닥에서는 구름 저항으로 1.5%씩 줄인다.',
      '화면에는 별도 표시 없이, 반투명 하이라이트를 공의 각도만큼 돌려 그려 구르는 것이 보이게 한다.',
    ],
    blocks: [
      ts('game/suika.ts · step()', `b.w = Math.max(-0.5, Math.min(0.5, b.w * 0.995))   // 1) 각속도 감쇠·제한
b.angle += b.w                                       // 1) 각도 갱신`),
      ts('game/suika.ts · collide() 공끼리', `// 속도 보정(imp)을 적용한 직후
const tx = -dy, ty = dx                              // 접선 방향
const vt = (b.vx - a.vx) * tx + (b.vy - a.vy) * ty - rb * b.w - ra * a.w   // 2) 미끄러지는 속도
const lim = 0.4 * imp                                // 3) 쿨롱 마찰 한도
const jt = Math.max(-lim, Math.min(lim, -vt / (3 * (1 / ma + 1 / mb))))
a.vx -= (jt * tx) / ma; a.vy -= (jt * ty) / ma       // 4) 접선 방향 속도 변화
b.vx += (jt * tx) / mb; b.vy += (jt * ty) / mb
a.w -= (2 * jt) / (ma * ra); b.w -= (2 * jt) / (mb * rb)   // 4) 회전 변화`),
      ts('game/suika.ts · collide() 바닥', `const dv = (b.vx - b.w * r) / 6        // 5) 미끄러짐을 절반으로
b.vx -= dv; b.w += (2 * dv) / r
b.vx *= 0.985; b.w *= 0.985                // 5) 구름 저항`),
      ts('game/suika.ts · drawBall()', `const a = angle + Math.atan2(-0.32, -0.3)          // 6) 기준(왼쪽 위) + 공의 각도
const d = r * Math.hypot(0.3, 0.32)
ctx.arc(x + Math.cos(a) * d, y + Math.sin(a) * d, r * 0.28, 0, Math.PI * 2)   // 6) 하이라이트가 같이 돈다`),
    ],
  },
  {
    key: '효과',
    summary: '합칠 때 링·불꽃·떠오르는 점수가 나오고, 새로 생긴 공은 커졌다가 원래 크기로 줄어든다.',
    notes: [
      '합쳐지는 순간 merge()가 burst()를 불러 링 1개, 불꽃 10개, 점수 글자 1개를 효과 목록에 넣는다.',
      '효과는 공과 별개의 목록이라 물리에 영향을 주지 않는다. 물리와 같은 1/60초 단계로 수명(life)이 줄고 0이 되면 지운다.',
      '게임이 끝나도 효과는 끝까지 재생한다(step 맨 앞에서 먼저 갱신하기 때문).',
      '링은 반지름이 커지며 흐려지고, 불꽃은 사방으로 퍼지며 중력을 받아 떨어진다.',
      '새 공은 pop 카운터(12프레임) 동안 그림 크기만 최대 18% 크게 그린다. 충돌 반지름은 그대로다.',
    ],
    blocks: [
      ts('game/suika.ts · burst() / stepFx()', `private burst(x, y, level, text) {
  this.fx.push({ kind: 'ring', x, y, life: 18, max: 18, level, r })          // 1) 링
  for (let i = 0; i < 10; i++) {                                              // 1) 불꽃 10개
    const ang = (Math.PI * 2 * i) / 10 + Math.random() * 0.4
    this.fx.push({ kind: 'spark', x, y, vx: Math.cos(ang) * sp, vy: Math.sin(ang) * sp, life: 26, max: 26, level, r: 2 })
  }
  this.fx.push({ kind: 'text', x, y: y - r * 0.3, vy: -0.7, life: 42, max: 42, level, r, text })   // 1) 점수
}

private stepFx() {
  for (const f of this.fx) {
    f.life--; f.x += f.vx; f.y += f.vy                                        // 2) 수명·이동
    if (f.kind === 'spark') { f.vy += 0.08; f.vx *= 0.97 }                    // 4) 불꽃은 떨어진다
  }
  this.fx = this.fx.filter((f) => f.life > 0)                                 // 2) 끝난 것 제거
}

private step() {
  this.stepFx()                                                               // 3) 끝난 뒤에도 갱신
  if (this.over) return
  ...
}`),
      ts('game/suika.ts · draw()', `this.circle(ctx, b.x, b.y, b.level, 1, b.angle, 1 + 0.18 * ((b.pop ?? 0) / 12))   // 5) 팝: 그림만 커짐`),
    ],
  },
  {
    key: '게임 오버',
    summary: '생긴 지 1초 넘은 공이 점선 위로 걸친 상태가 2초 이어지면 끝난다. 막 떨어지는 공은 age로 제외한다.',
    notes: [
      '방금 투하한 공은 점선 위를 지나가므로 age(프레임 수)가 60을 넘은 공만 검사한다.',
      '걸친 공이 하나라도 있으면 카운터를 올리고, 없으면 0으로 되돌려 순간적으로 넘는 것은 봐준다.',
      '카운터가 120(2초)을 넘으면 게임을 끝낸다.',
    ],
    blocks: [ts('game/suika.ts · checkOver()', `const risky = this.balls.some(
  (b) => b.age > 60 && b.y - RADII[b.level] < LINE_Y,   // 1) 1초 지난 공이 선 위로
)
this.overTime = risky ? this.overTime + 1 : 0           // 2) 걸치면 +1, 아니면 초기화
if (this.overTime > 120) this.over = true               // 3) 2초 지속되면 종료`)],
  },
  {
    key: '입력',
    summary: '포인터 위치를 판 좌표(360×520)로 환산해 조준하고, 클릭이나 스페이스로 투하한다. 화면 배율은 캔버스 해상도에 반영한다.',
    notes: [
      'CSS로 줄어든 캔버스에서도 맞도록 getBoundingClientRect 비율로 판 좌표로 환산한다.',
      '조준 위치는 공이 벽 밖으로 나가지 않게 반지름만큼 안쪽으로 제한한다. 카드가 접혀 캔버스 폭이 0이면 좌표를 계산하지 않는다(0으로 나누면 NaN이 되어 판이 망가진다).',
      '투하 후 30프레임(0.5초) 쿨다운을 둔다. 대기 공이 바뀌면 화면 좌표를 다시 거치지 않고 aimX를 직접 범위 안으로 맞춘다.',
      '왼쪽 버튼(터치 포함)으로 판에서 눌렀다가 판에서 뗄 때만 투하한다. 오른쪽 클릭이나 판 밖에서 시작한 누름은 무시한다.',
    ],
    blocks: [
      ts('game/suika.ts · aim() / drop()', `aim(clientX: number) {
  const r = this.canvas.getBoundingClientRect()
  if (r.width <= 0) return                           // 2) 접힌 카드: 0으로 나누지 않는다
  const x = ((clientX - r.left) / r.width) * W       // 1) 화면 → 판 좌표
  this.aimX = this.clamp(x)                          // 2) 벽 밖 제한(반지름만큼 안쪽)
}

drop() {
  if (this.over || this.cooldown > 0) return
  this.balls.push({ x: this.aimX, y: RADII[this.current], vx: 0, vy: 0, angle: 0, w: 0, level: this.current, age: 0 })
  this.current = this.next
  this.next = this.randLevel()
  this.cooldown = 30                                  // 3) 0.5초
  this.clampAim()                                     // 3) 바뀐 대기 공 크기에 맞게 직접 보정
}`),
      ts('views/SuikaView.vue', `let pressed = false
function onDown(e: PointerEvent) {
  if (e.button !== 0) return                          // 4) 왼쪽 버튼만
  pressed = true
  game?.aim(e.clientX)
}
function onUp(e: PointerEvent) {
  if (e.button !== 0 || !pressed) return              // 4) 판에서 눌렀을 때만
  pressed = false
  game?.aim(e.clientX); game?.drop()                  // 4) 뗄 때 투하
}
// <canvas @pointerdown="onDown" @pointerup="onUp" @pointercancel/@pointerleave="pressed = false" />`),
    ],
  },
]

/** 조작·규칙 항목별 구현. */
export const rules: Step[] = [
  {
    key: '조준',
    summary: '마우스(터치)를 좌우로 움직여 위치를 정한다.',
    notes: [
      '캔버스의 pointermove/pointerdown 이벤트에서 aim(clientX)을 호출한다.',
      '화면 좌표를 캔버스 표시 크기 비율로 판 좌표(360)로 환산한다. 화면이 줄어도 위치가 맞는다.',
      '현재 공의 반지름만큼 벽에서 떨어지게 제한해 공이 벽 밖으로 나가지 않는다.',
      '세로 가이드선과 반투명 공은 draw()에서 aimX 위치에 그린다.',
    ],
    blocks: [
      ts('views/SuikaView.vue', `<canvas @pointermove="game?.aim($event.clientX)"   <!-- 1) 움직일 때마다 조준 -->
        @pointerdown="onDown" />   <!-- 1) 왼쪽 버튼이면 pressed = true 로 두고 조준 -->`),
      ts('game/suika.ts · aim()', `aim(clientX: number) {
  const r = this.canvas.getBoundingClientRect()
  if (r.width <= 0) return                           // (접힌 카드는 계산하지 않는다)
  const x = ((clientX - r.left) / r.width) * W       // 2) 화면 → 판 좌표
  this.aimX = this.clamp(x)                          // 3) 벽 밖 제한(반지름만큼 안쪽)
}`),
      ts('game/suika.ts · draw()', `ctx.moveTo(this.aimX, 0); ctx.lineTo(this.aimX, H)   // 4) 세로 가이드선
this.circle(ctx, this.aimX, RADII[this.current] + 4, this.current, ...)   // 4) 반투명 공`),
    ],
  },
  {
    key: '투하',
    summary: '클릭(터치를 뗄 때) 또는 스페이스바. 직후 0.5초는 쿨다운.',
    notes: [
      '클릭은 판에서 왼쪽 버튼을 눌렀다 뗄 때(pointerup), 스페이스바는 window keydown에서 drop()을 호출한다.',
      '쿨다운은 프레임 카운터다. 투하 시 30으로 두고 물리 한 단계마다 1씩 줄이므로 0.5초(60fps 기준)다.',
      '쿨다운 중이거나 게임이 끝났으면 무시한다.',
      '쿨다운 중 대기 공은 반투명(0.35)으로 그린다.',
      '버튼·입력창·선택 상자·편집 영역에 포커스가 있거나, 키를 누르고 있거나(repeat), Ctrl/Alt/Shift/Meta와 함께 누르면 투하하지 않는다.',
      '판이 화면에 보일 때만 스페이스바를 게임 입력으로 쓴다. 아래쪽 카드를 읽을 때는 평소처럼 스크롤된다.',
      '투하한 공은 대기 중이던 단계가 되고, 다음 공이 대기로 올라온다.',
      '마우스로 버튼·체크박스(`새 게임`, `튕기기`, 카드 머리글 등)를 누른 직후에는 포커스를 풀어, 이어서 스페이스바로 바로 투하할 수 있다(포커스가 남으면 스페이스가 그 컨트롤을 다시 눌러 게임이 초기화되거나 카드가 접힌다). 키보드로 누른 경우는 포커스를 유지해 키보드 사용자가 위치를 잃지 않는다.',
    ],
    blocks: [
      ts('game/suika.ts · drop()', `drop() {
  if (this.over || this.cooldown > 0) return          // 3) 쿨다운 중/종료 시 무시
  this.balls.push({ x: this.aimX, y: RADII[this.current], vx: 0, vy: 0, angle: 0, w: 0, level: this.current, age: 0 })
  this.current = this.next                            // 7) 대기 공이 떨어지고
  this.next = this.randLevel()                        // 7) 다음 공이 대기로
  this.cooldown = 30                                  // 2) 0.5초
}`),
      ts('game/suika.ts · step() / draw()', `if (this.cooldown > 0) this.cooldown--             // 2) 물리 한 단계마다 1씩 감소
this.circle(ctx, this.aimX, ..., this.cooldown > 0 ? 0.35 : 1)   // 4) 쿨다운 중 반투명`),
      ts('views/SuikaView.vue', `<canvas @pointerup="onUp" />          <!-- 1) 판에서 왼쪽 버튼을 뗄 때 -->

function onKey(e: KeyboardEvent) {
  if (e.code !== 'Space' || !game) return
  if (e.repeat || e.ctrlKey || e.metaKey || e.altKey || e.shiftKey) return   // 5) 반복·조합키 무시
  const t = e.target as HTMLElement
  if (['BUTTON', 'INPUT', 'TEXTAREA', 'SELECT'].includes(t.tagName) || t.isContentEditable) return   // 5) 포커스 중이면 양보
  if (!boardVisible()) return                         // 6) 판이 화면에 보일 때만
  e.preventDefault()                                  // 스크롤 방지
  game.drop()                                         // 1) 스페이스바
}
window.addEventListener('keydown', onKey)

function releaseFocusAfterMouse(e: MouseEvent) {
  if (e.detail === 0) return                          // 8) 키보드(Enter/Space)로 일어난 click 은 detail 0 → 포커스 유지
  if (!root.value?.contains(e.target as Node)) return // 8) 이 페이지 안의 컨트롤만
  setTimeout(() => {                                  // 8) 라벨을 눌렀을 때는 포커스가 옮겨 간 뒤에 푼다
    const a = document.activeElement as HTMLElement | null
    if (a?.matches('button, input[type="checkbox"]')) a.blur()   // 8) 마우스로 누른 뒤 포커스 해제
  })
}
// onMounted:       window.addEventListener('click', releaseFocusAfterMouse, true)
// onBeforeUnmount: window.removeEventListener('click', releaseFocusAfterMouse, true)`),
    ],
  },
  {
    key: '튕기기',
    summary: '옵션을 켜면 바닥·벽·공끼리 탄성 있게 튕긴다. 게임 중에도 켜고 끌 수 있다.',
    notes: [
      '카드 아래 체크박스가 game.bouncy 값을 바꾼다. 다음 물리 단계부터 바로 적용된다.',
      '꺼짐: 벽·바닥 0.2, 공끼리 0.1이라 거의 튕기지 않는다. 켬: 각각 0.55, 0.6이다.',
      '바닥에서 세로 속도가 1.5보다 작으면 0으로 멈춘다. 반발이 크면 끝없이 잔진동하기 때문이다.',
      '공끼리도 천천히 닿으면(상대 속도 1 미만) 반발 없이 얹혀, 쌓은 공이 계속 떨지 않는다.',
    ],
    blocks: [
      ts('views/SuikaView.vue', `<input v-model="bouncy" type="checkbox" />      <!-- 1) -->
watch(bouncy, (v) => { game.bouncy = v })`),
      ts('game/suika.ts · collide()', `const wallE = this.bouncy ? 0.55 : 0.2     // 2) 벽·바닥
const ballE = this.bouncy ? 0.6 : 0.1      // 2) 공끼리
if (b.vy > 0) b.vy = b.vy < 1.5 ? 0 : b.vy * -wallE   // 3) 바닥으로 향할 때만 반사, 느리면 멈춤
const e = -rv < 1 ? 0 : ballE                     // 4) 천천히 닿으면 얹힘`),
    ],
  },
  {
    key: '최고 점수',
    summary: '최고 점수는 이 브라우저의 로컬스토리지에 저장되어 새로고침해도 유지된다.',
    notes: [
      '키는 note.suika.best. 게임을 만들 때 읽어 오고, 화면에는 생성 직후 한 번 동기화해 반영한다.',
      '점수가 최고를 넘는 순간(합칠 때, 게임 오버 때) 바로 저장한다. 매 프레임 저장하지는 않는다.',
      '저장소를 못 쓰는 환경(시크릿 모드, 용량 초과, 차단)에서도 게임이 멈추지 않도록 읽기·쓰기를 try/catch로 감싼다. 이때는 새로고침하면 최고 점수가 0부터 시작한다.',
      '값이 숫자가 아니거나 0 이하이면 0으로 취급한다.',
      '탭을 여러 개 열어 두었을 수 있으므로 저장할 때 저장돼 있는 값과 비교해 큰 쪽을 남긴다.',
      '브라우저(와 접속 주소)별 값이라 다른 기기와는 공유되지 않는다.',
    ],
    blocks: [
      ts('game/suika.ts', `private bumpBest() {
  if (this.score <= this.best) return                // 2) 넘을 때만
  this.best = this.score
  saveBest(this.best)
}

function loadBest(): number {
  try {
    const n = Number(localStorage.getItem(BEST_KEY))   // 1) 'note.suika.best'
    return Number.isFinite(n) && n > 0 ? n : 0          // 4) 이상한 값은 0
  } catch {
    return 0                                            // 3) 저장소를 못 쓰면 0
  }
}

function saveBest(n: number) {
  try {
    const stored = Number(localStorage.getItem(BEST_KEY)) || 0   // 5) 다른 탭이 저장한 값
    localStorage.setItem(BEST_KEY, String(Math.max(stored, n)))  // 5) 큰 쪽을 남긴다
  } catch { /* 3) 실패는 무시 */ }
}`),
      ts('views/SuikaView.vue', `game = new Suika(canvas.value!, sync)
sync()   // 1) 생성 중 콜백은 game 대입 전이라 건너뛰므로 한 번 더 반영`),
    ],
  },
  {
    key: '다음 공',
    summary: '새로 나오는 공은 작은 원 5종 중에서 무작위로 정해진다.',
    notes: [
      '0~4단계 중 균등 확률로 고른다.',
      '큰 원은 직접 나오지 않고 합쳐서만 만들 수 있다.',
      '현재 공과 다음 공을 미리 정해 두어 오른쪽 패널에 다음 공의 색을 보여 준다.',
    ],
    blocks: [
      ts('game/suika.ts', `private randLevel() {
  return Math.floor(Math.random() * 5)    // 1) 0~4단계 균등 · 2) 5단계 이상은 합쳐야만 생김
}

reset() {
  this.current = this.randLevel()
  this.next = this.randLevel()            // 3) 다음 공을 미리 정해 둔다 → 화면에 표시
}`),
    ],
  },
  {
    key: '합치기',
    summary: '같은 단계 둘이 닿으면 한 단계 위로 합쳐지고 점수를 얻는다.',
    notes: [
      '충돌 처리가 끝난 뒤 merge()가 같은 단계 쌍을 찾는다. 거리가 반지름 합 + 0.5 이하이면 닿은 것으로 본다.',
      '두 원을 지우고 중간 위치에 한 단계 큰 원을 만든다. 속도는 둘의 평균이다.',
      '점수는 합쳐지기 전 단계의 값이다. 단계가 클수록 커진다: 1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66.',
    ],
    blocks: [
      ts('game/suika.ts · SCORES / merge()', `const SCORES = [1, 3, 6, 10, 15, 21, 28, 36, 45, 55, 66]   // 3) 단계별 점수

if (a.level === b.level && dist <= RADII[a.level] + RADII[b.level] + 0.5) {   // 1) 같은 단계 + 닿음
  a.dead = b.dead = true
  this.score += SCORES[a.level]                         // 3) 합치기 전 단계의 점수
  if (a.level < MAX_LEVEL) {                            // (최대 단계는 "보너스" 항목)
    added.push({
      x: (a.x + b.x) / 2, y: (a.y + b.y) / 2,           // 2) 중간 위치
      vx: (a.vx + b.vx) / 2, vy: (a.vy + b.vy) / 2,      // 2) 평균 속도
      angle: a.angle, w: (a.w + b.w) / 2,
      level: a.level + 1, age: 0, pop: 12,
    })
  }
}`),
    ],
  },
  {
    key: '보너스',
    summary: '가장 큰 원 둘이 만나면 사라지며 +100점.',
    notes: [
      '더 키울 단계가 없으므로(MAX_LEVEL) 새 원을 만들지 않고 둘 다 지운다.',
      '지우면서 일반 합치기 점수에 100점을 더한다. 지워지는 것은 이 두 공뿐이고 나머지 공은 그대로 남는다.',
    ],
    blocks: [ts('game/suika.ts · merge()', `if (a.level < MAX_LEVEL) {
  added.push({ /* 한 단계 큰 원 */ })
} else {
  // 1) 새 원을 만들지 않는다 → 둘 다 사라짐
  this.score += 100          // 2) 보너스
}`)],
  },
  {
    key: '종료',
    summary: '점선 위로 공이 2초 넘게 걸쳐 있으면 게임 오버.',
    notes: [
      '방금 투하한 공은 점선 위를 지나가므로 age가 60프레임(1초)을 넘은 공만 검사한다.',
      '걸친 공이 있는 동안 카운터를 올리고, 없어지면 0으로 되돌린다.',
      '카운터가 120(2초)을 넘으면 게임을 끝낸다.',
      '끝나면 캔버스에 어두운 막과 GAME OVER, 점수를 그린다. 다시 시작은 캔버스가 아니라 점수 밑의 Vue 버튼이 reset()을 불러 처리한다.',
      '점선은 걸친 공이 있는 동안 위험을 알리려고 빨간색(--danger)으로 바뀐다.',
    ],
    blocks: [
      ts('game/suika.ts · checkOver()', `const risky = this.balls.some(
  (b) => b.age > 60 && b.y - RADII[b.level] < LINE_Y,   // 1) 1초 지난 공만
)
this.overTime = risky ? this.overTime + 1 : 0           // 2) 걸치면 +1, 아니면 초기화
if (this.overTime > 120) this.over = true               // 3) 2초 지속되면 종료`),
      ts('game/suika.ts · draw()', `ctx.strokeStyle = this.overTime > 0 ? v('--danger') : v('--border')   // 5) 위험하면 빨간 점선

if (this.over) {                                        // 4) 종료 화면
  ctx.fillStyle = v('--game-overlay'); ctx.fillRect(0, 0, W, H)   // 색은 style.css 토큰
  ctx.fillStyle = v('--game-overlay-text')
  ctx.fillText('GAME OVER', W / 2, H / 2 - 6)
}`),
      ts('views/SuikaView.vue', `<button v-if="ui.over" class="primary restart" @click="game?.reset()">다시 시작</button>   <!-- 4) -->`),
    ],
  },
]
