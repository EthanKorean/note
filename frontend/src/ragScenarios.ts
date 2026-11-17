import { retrievers } from './ragModes'
import type { RagEvalParams } from './ragTypes'

/** "시나리오로 따라 하기" 카드의 내용. 시나리오를 추가하거나 조건을 바꾸면 여기만 고친다. */
export interface Scenario {
  id: string
  no: number
  title: string
  /** 왜 이 실험을 하나 */
  why: string
  /** 결과에서 무엇을 보면 되나 */
  look: string
  /** 내 PC에서 쟀을 때의 결과(내 결과와 비교해 보라는 용도, 정답이 아니다) */
  reference: string
  conditions: RagEvalParams[]
}

const ALL = retrievers.map((m) => m.value)
const cond = (chunker: string, size: number, overlap = 0): RagEvalParams => ({ chunker, size, overlap, retrievers: ALL })

export const scenarios: Scenario[] = [
  {
    id: 'scn-chunk',
    no: 1,
    title: '문서를 어떻게 자르느냐',
    why: '검색 방식은 그대로 두고 자르는 방식만 바꿨을 때 정답을 찾는 비율이 달라지는지 본다. 모델을 바꾸기 전에 가장 먼저 확인할 변수다',
    look: '같은 검색 방식끼리 청킹별 MRR을 비교한다(MRR이 높을수록 정답이 앞 순위에 나온다). 제목 단위는 조각 앞에 "파일 > 제목"이 붙는다는 점도 기억해 둔다',
    reference: '제목 단위 + 하이브리드가 MRR 0.810으로 가장 높았고, 고정 길이는 0.647 ~ 0.739, 문단은 0.678 ~ 0.747',
    conditions: [cond('fixed', 500, 50), cond('paragraph', 500), cond('heading', 500)],
  },
  {
    id: 'scn-retriever',
    no: 2,
    title: '뜻으로 찾을까, 단어로 찾을까',
    why: '벡터(뜻), 키워드(단어), 하이브리드(둘을 합침) 중 무엇이 이 문서와 질문에 맞는지 본다. 합치면 항상 좋아지는지도 확인한다',
    look: '한 청킹 조건의 세 검색 방식을 비교한다. 질문에 숫자나 고유한 이름이 많으면 키워드가 유리할 수 있다. 평균 시간도 같이 본다(벡터는 질문을 벡터로 바꾸는 시간이 든다)',
    reference: '제목 단위 500자: 벡터 0.745 / 키워드 0.752 / 하이브리드 0.810. 시간은 벡터와 하이브리드가 약 3ms, 키워드가 0.03ms. 고정 길이에서는 키워드(0.739)가 하이브리드(0.714)보다 높았다',
    conditions: [cond('heading', 500)],
  },
  {
    id: 'scn-size',
    no: 3,
    title: '조각 크기 300 / 500 / 1000자',
    why: '조각이 작으면 정확하지만 문맥이 끊기고, 크면 문맥은 있지만 엉뚱한 내용이 섞인다. 이 문서들에서는 어디가 좋은지 본다',
    look: '조각 크기가 커질수록 MRR이 오르는지 본다. 단, 이 평가는 "정답 문구가 조각 안에 있느냐"만 보기 때문에 큰 조각에 유리하다. 큰 조각은 모델에게 넘기는 글이 늘어난다는 대가가 있다(평균 글자 수 확인)',
    reference: '제목 단위 + 하이브리드: 300자 0.728 → 500자 0.810 → 1000자 0.840. 고정 길이 + 키워드: 0.668 → 0.739 → 0.799',
    conditions: [
      cond('heading', 300), cond('heading', 500), cond('heading', 1000),
      cond('paragraph', 300), cond('paragraph', 500), cond('paragraph', 1000),
      cond('fixed', 300, 50), cond('fixed', 500, 50), cond('fixed', 1000, 50),
    ],
  },
  {
    id: 'scn-overlap',
    no: 4,
    title: '고정 길이에서 겹침을 두면',
    why: '고정 길이로 자르면 정답이 조각 경계에서 둘로 쪼개질 수 있다. 앞 조각과 일부를 겹치면 이를 막지만 같은 내용이 여러 조각에 중복된다',
    look: '겹침이 늘수록 조각 수가 늘고 점수가 어떻게 변하는지, "정답이 잘려 어디에도 없는 질문"이 줄어드는지 본다',
    reference: '겹침 0자일 때 정답이 잘려 어디에도 없는 질문이 1개였다. 그런데 키워드 MRR은 겹침 0자가 0.769로 가장 높았고 50자 0.739, 150자 0.732. 벡터는 0.653 → 0.647 → 0.621',
    conditions: [cond('fixed', 500, 0), cond('fixed', 500, 50), cond('fixed', 500, 150)],
  },
  {
    id: 'scn-limit',
    no: 5,
    title: '조각이 너무 길면 벡터가 앞부분만 본다',
    why: '임베딩 모델에는 입력 한도(512 토큰)가 있다. 조각이 이보다 길면 뒤쪽이 잘려서 벡터에 반영되지 않는다. 조각 크기를 키울 때 놓치기 쉬운 함정이다',
    look: '결과의 "뒤가 잘린 조각" 수를 본다. 조각이 길어질수록 키워드(조각 전체를 봄) 점수와 벡터(앞부분만 봄) 점수의 차이가 벌어지는지 본다',
    reference: '1000자에서는 조각 24개 중 19개, 2000자는 13개 중 11개가 잘렸다. 키워드 MRR 0.787 → 0.840 → 0.910, 벡터 0.664 → 0.643 → 0.696. 한국어는 이 모델에서 1.7자 정도가 1토큰이라 800~900자부터 잘리기 시작한다',
    conditions: [cond('fixed', 1000), cond('fixed', 2000), cond('fixed', 3000)],
  },
]

/** 6번 시나리오: 직접 물어 보는 예시 질문. 결과를 눈으로 보고 판단한다(점수는 없다). */
export const tryQuestions: { q: string; look: string }[] = [
  {
    q: '원본 지연이 20ms일 때 Redis는 prebuilt보다 몇 배 빨랐나?',
    look: '숫자와 고유한 이름("20ms", "prebuilt")이 들어 있다. 키워드 검색이 유리한 질문이다. 벡터, 키워드, 하이브리드로 바꿔 가며 1위가 같은지 본다',
  },
  {
    q: '메모리가 부족해지면 어떤 방식이 더 좋았어?',
    look: '문서는 "메모리 한도", "제거 정책", "적중률"이라고 쓴다. 질문이 다른 말을 쓴 경우라, 뜻 기반 검색이 따라오는지 본다',
  },
  {
    q: '오늘 점심 메뉴 추천해 줘',
    look: '문서에 전혀 없는 질문이다. 그래도 검색은 "가장 가까운 것"을 돌려준다. 못 찾았다는 신호가 없다는 점이 할루시네이션 문제로 이어진다',
  },
]

/** 1~5번 시나리오의 조건을 순서대로 모으되, 같은 조건은 한 번만 실행한다. */
export function allConditions(): RagEvalParams[] {
  const seen = new Set<string>()
  const out: RagEvalParams[] = []
  for (const s of scenarios) {
    for (const c of s.conditions) {
      const k = `${c.chunker}|${c.size}|${c.overlap}`
      if (!seen.has(k)) {
        seen.add(k)
        out.push(c)
      }
    }
  }
  return out
}
