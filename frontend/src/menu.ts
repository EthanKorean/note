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
    items: [{ path: '/bulk-insert', label: '대용량 적재', desc: '도로명주소 642만 건 INSERT 비교' }],
  },
  {
    title: '게임',
    items: [{ path: '/suika', label: '수박 게임', desc: '원을 합쳐 수박 만들기' }],
  },
]
