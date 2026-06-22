package com.drawe.backend.domain.llm.search;

import java.util.List;

/**
 * LLM 키워드 추출 폴백 인터페이스.
 *
 * <p>{@link KomoranKeywordExtractor} 가 사전 미스율이 임계(30%) 초과 시 호출. 입력은 형태소 분석 전의 원본 메시지({@code
 * cleanedMessage}) — LLM 이 문장 컨텍스트를 보고 영문 검색 키워드를 재추출.
 *
 * <p>구현체:
 *
 * <ul>
 *   <li>{@link NoopKeywordExtractorFallback} — 기본 (Grok 연결 전 임시)
 *   <li>{@code GrokKeywordExtractorFallback} — Grok 통합 시 추가 예정
 * </ul>
 *
 * <p>호출자가 책임지는 거:
 *
 * <ul>
 *   <li>호출 빈도 (사전 미스율 측정 후 임계 초과 시에만)
 *   <li>비용·지연 (LLM 콜 = $0.001/호출, 200~500ms)
 * </ul>
 *
 * <p>구현체가 책임지는 거:
 *
 * <ul>
 *   <li>LLM 호출 + Structured Output 검증
 *   <li>네트워크 오류 처리 (timeout·retry·circuit breaker)
 *   <li>빈 결과 반환 가능 (실패 시 안전한 fallback)
 * </ul>
 */
public interface KeywordExtractorFallback {

  /**
   * 원본 메시지에서 영문 검색 키워드 추출.
   *
   * @param cleanedMessage A의 TextPreprocessor 출력
   * @return 영문 키워드 리스트, 실패 시 빈 리스트 (null 반환 X)
   */
  List<String> extract(String cleanedMessage);
}
