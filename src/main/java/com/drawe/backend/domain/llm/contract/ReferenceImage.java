package com.drawe.backend.domain.llm.contract;

import java.math.BigDecimal;
import java.util.List;

/**
 * 검색 결과 레퍼런스 이미지 (계약 계층).
 *
 * <p>기존 {@code ImageResult} (search 도메인) 와의 관계:
 *
 * <ul>
 *   <li>의존 차단 목적으로 별도 타입 정의
 *   <li>B의 {@code SearchExecutor} 안에서 {@code ImageResult} → {@code ReferenceImage} 어댑터 변환
 *   <li>변환 시 1-based {@code index} 부여 → 인용 무결성 검사 키
 * </ul>
 *
 * <p>{@code imageId} vs {@code index} 분리:
 *
 * <ul>
 *   <li>{@code index} — 사용자가 [1], [2], [3] 으로 보는 번호. LLM 응답의 "1번처럼" 인용 매칭에 사용.
 *   <li>{@code imageId} — DB 키. 메트릭·로깅·디버깅용.
 * </ul>
 *
 * @param imageId DB 키 (메트릭/로깅용)
 * @param index 1-based 인덱스 (인용 무결성)
 * @param url 이미지 URL
 * @param photographer 작가명
 * @param score CLIP 유사도 점수
 * @param tags technique/subject/mood/utility/freeTags 합산
 */
public record ReferenceImage(
    Long imageId,
    int index,
    String url,
    String photographer,
    BigDecimal score,
    List<String> tags) {}
