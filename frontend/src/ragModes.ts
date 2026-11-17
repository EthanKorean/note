/** 청킹 방식과 검색 방식의 이름·설명. 화면 곳곳에서 같은 문구를 쓰려고 한 곳에 둔다. */
export interface RagMode {
  value: string
  label: string
  summary: string
}

export const chunkers: RagMode[] = [
  {
    value: 'fixed',
    label: '고정 길이',
    summary: '글자 수로 기계적으로 자릅니다(예: 500자, 앞 조각과 50자 겹침). 가장 단순하지만 문장이나 표 중간이 잘려 정답이 두 조각에 걸칠 수 있습니다.',
  },
  {
    value: 'paragraph',
    label: '문단',
    summary: '빈 줄로 나뉜 문단을 정한 크기까지 이어 붙입니다. 문단은 안 끊기지만 서로 다른 제목의 내용이 한 조각에 섞일 수 있습니다.',
  },
  {
    value: 'heading',
    label: '제목 단위',
    summary: '마크다운 제목(#) 단위로 자르고, 길면 문단으로 더 쪼갭니다. 조각 앞에 "파일 > 제목"을 붙여 검색해서 제목에 있는 정보도 활용합니다.',
  },
]

export const retrievers: RagMode[] = [
  { value: 'vector', label: '벡터 (뜻)', summary: '질문과 조각을 벡터로 바꿔 뜻이 가까운 순서로 찾습니다. 다른 표현으로 물어도 찾지만 숫자나 고유한 이름에는 약할 수 있습니다.' },
  { value: 'bm25', label: '키워드 (BM25)', summary: '질문의 단어가 조각에 나오는 정도로 찾습니다. 숫자와 이름처럼 글자가 맞아야 하는 질문에 강하고, 말을 바꾸면 못 찾습니다.' },
  { value: 'hybrid', label: '하이브리드 (RRF)', summary: '벡터와 키워드의 순위를 합칩니다. 각 순위의 1/(60+순위)를 더해 다시 줄을 세웁니다.' },
]

const label = (list: RagMode[], v: string) => list.find((m) => m.value === v)?.label ?? v
export const chunkerLabel = (v: string) => label(chunkers, v)
export const retrieverLabel = (v: string) => label(retrievers, v)
