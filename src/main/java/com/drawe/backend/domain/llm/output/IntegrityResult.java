package com.drawe.backend.domain.llm.output;

/**
 * {@link OutputIntegrityChecker} 의 결과 — 정정된 출력 + 위반 카운트(설계 §5).
 *
 * <p>카운트는 ⑦ 의 Micrometer 카운터({@code drawe.output.hallucinated_citation},
 * {@code drawe.output.citation_removed})로 박제될 입력이다. DoD: {@code hallucinatedCount == 0}.
 *
 * @param output                정정된 출력(환각 인용 제거 후 본문·citations).
 * @param hallucinatedCitations citations 슬롯에서 유효 범위 밖이라 제거된 인덱스 수.
 * @param hallucinatedBodyTokens 본문 {@code [N]} 중 유효 범위 밖이라 제거된 토큰 수.
 */
public record IntegrityResult(
    ComposedOutput output, int hallucinatedCitations, int hallucinatedBodyTokens) {

  /** 환각 인용이 하나라도 있었는지 — DoD(0건) 위반 판정·메트릭 분기용. */
  public boolean hadHallucination() {
    return hallucinatedCitations > 0 || hallucinatedBodyTokens > 0;
  }
}
