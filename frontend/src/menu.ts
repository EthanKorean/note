/** 사이드 메뉴. 새 테스트를 추가할 때는 여기에 항목을 넣고 router.ts 에 화면을 연결한다. */
export interface MenuItem {
  path: string
  label: string
  desc?: string
  /** true면 아직 화면이 없는 항목(회색, 이동 불가) */
  soon?: boolean
}

export interface MenuGroup {
  title: string
  items: MenuItem[]
}

export const menu: MenuGroup[] = [
  { title: '', items: [{ path: '/', label: '홈' }] },
  {
    title: 'DB',
    items: [
      { path: '/bulk-insert', label: '대용량 적재', desc: '도로명주소 642만 건 INSERT 비교' },
      { path: '/lock', label: 'DB 락 전략', desc: '쇼핑몰 주문으로 초과 판매·데드락 재현과 락 전략 비교' },
    ],
  },
  {
    title: '캐시',
    items: [{ path: '/image-cache', label: '이미지 캐싱', desc: '인기·한시 상품 이미지를 Redis로 캐싱' }],
  },
  {
    title: 'AI',
    items: [
      { path: '/ai-terms', label: 'AI 용어 정리', desc: 'RAG, 에이전트, MCP 같은 새 용어와 카페 예제' },
      { path: '/rag', label: 'RAG 검색', desc: '문서를 잘라 벡터로 만들고 청킹·검색 방식별 정답률 비교' },
    ],
  },
  {
    title: '게임',
    items: [
      { path: '/suika', label: '수박 게임', desc: '원을 합쳐 수박 만들기' },
      { path: '/catch', label: '캐치마인드', desc: '그림을 그리고 제시어를 맞히는 멀티플레이' },
    ],
  },
]
