package com.drawe.backend.domain.llm.output;

import java.util.List;

/**
 * COMPOSE 단계의 구조화 응답(설계 §4.1·§5) — 파싱·무결성 검사를 거친 합성 결과.
 *
 * <p>Grok 의 {@code draw_guide_response} 스키마({@code message,citations,offer_generate})를
 * {@link OutputParser} 가 이 DTO 로 옮기고, {@link OutputIntegrityChecker} 가 환각 인용을 제거해
 * 정정본을 다시 이 DTO 로 돌려준다(재호출 없음).
 *
 * @param message       사용자에게 보일 가이드 본문(무결성 검사 후 정정본).
 * @param citations     본문이 실제 인용한 references 1-based 인덱스(검사 후 살아남은 것만, 오름차순·중복 제거).
 * @param offerGenerate LLM 의 생성 제안 의견(보조 신호). 최종 버튼 노출은 시스템이 결정한다.
 */
public record ComposedOutput(String message, List<Integer> citations, boolean offerGenerate) {

  public ComposedOutput {
    citations = citations == null ? List.of() : List.copyOf(citations);
  }
}
