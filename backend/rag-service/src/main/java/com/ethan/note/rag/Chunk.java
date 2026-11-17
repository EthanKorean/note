package com.ethan.note.rag;

/**
 * 검색 단위 조각.
 *
 * @param heading   조각이 속한 제목 경로(예: "4. 결과 > 4.5 메모리 한도"). 제목이 없으면 빈 문자열
 * @param text      문서에서 잘라 낸 원문 (화면 표시와 정답 판정에 쓴다)
 * @param indexText 임베딩과 키워드 검색에 넣는 글. 제목 단위 청킹은 앞에 "파일 > 제목"을 붙인다
 */
public record Chunk(int id, String source, String heading, String text, String indexText) {
}
