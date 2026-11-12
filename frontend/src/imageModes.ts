/** 이미지를 내려 주는 방식(백엔드 CacheMode)과 화면 설명 */
export interface ImageMode {
  value: 'nocache' | 'prebuilt' | 'redis'
  label: string
  summary: string
}

export const imageModes: ImageMode[] = [
  {
    value: 'nocache',
    label: '캐시 없음 (요청마다 리사이징)',
    summary: '요청이 올 때마다 원본(1200x900)을 읽어 요청한 크기로 줄여서 내려 줍니다. 가장 단순하지만 CPU를 많이 쓰는 최악의 기준선이라, 실제 서비스에서는 거의 쓰지 않습니다.',
  },
  {
    value: 'prebuilt',
    label: '미리 만든 이미지 (파일 읽기만)',
    summary:
      '업로드할 때 웹·앱·썸네일 크기를 한 번만 줄여 파일로 저장해 두고, 요청이 오면 그 파일을 읽기만 합니다. 쇼핑몰이 보통 쓰는 기본 구조이고 다른 방식의 기준선입니다. 걸리는 시간은 대부분 "원본 저장소 지연"(원격 스토리지 흉내) 값이며, 캐시 층이 없어서 적중률은 표시되지 않습니다.',
  },
  {
    value: 'redis',
    label: 'Redis 캐시 (자주 보는 것만)',
    summary:
      '미리 만든 이미지 위에 얹는 캐시 층입니다. Redis에 없을 때만 미리 만든 파일을 읽으러 가고, 최근 자주 찾는 이미지만 Redis에 올립니다. 윈도 안에 기준 횟수 이상 캐시를 못 찾은 상품만 올라가고, TTL이 지나거나 메모리가 차면 빠집니다. 인기 상품이 바뀌면 적중률이 어떻게 변하는지 봅니다.',
  },
]

export const imageModeLabel = (m: string) => imageModes.find((x) => x.value === m)?.label.split(' (')[0] ?? m
