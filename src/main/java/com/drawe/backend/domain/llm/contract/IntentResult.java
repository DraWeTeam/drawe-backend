package com.drawe.backend.domain.llm.contract;

import java.util.List;

/**
 * Intent 분류 결과. PLAN 단계의 출력 = EXECUTE 단계의 입력.
 *
 * <p>개발자 A(결정·오케스트레이션) → 개발자 B(검색·데이터) 핸드오프 DTO. 이 record의 필드는 양 트랙의 계약이므로 변경 시 양쪽 합의 필수.
 *
 * <p>S0(베타 중) 상태: 정의만 존재, 어디서도 사용하지 않음. Phase 1(베타 후)에서 {@code IntentClassifier} 가 생성, {@code
 * WorkflowService} 가 소비.
 *
 * <p>이전에 별도 IntentContext 를 둘지 검토했으나 이 record 의 슬롯이 충분하므로 합치기로 결정.
 *
 * @param code 분류된 의도 코드. 룰 매치 또는 경량 LLM 분류 결과.
 * @param referencedImages 사용자가 명시적으로 참조한 이미지 인덱스 목록 (예: "[2]번" → [2]). 1-based, 사용자 표시 순서 그대로. 이전
 *     plan의 IntentCode 009를 슬롯으로 분리한 결과. 없으면 빈 리스트.
 * @param hasUploadedImage 사용자가 본인 작업물 이미지를 업로드했는지 (010 SELF_CRITIQUE 트리거).
 * @param tier 분류가 어느 폴백 단계에서 결정됐는지. Micrometer 태그용.
 */
public record IntentResult(
    IntentCode code, List<Integer> referencedImages, boolean hasUploadedImage, Tier tier) {

  public IntentResult {
    referencedImages = referencedImages == null ? List.of() : List.copyOf(referencedImages);
  }

  /**
   * 분류가 결정된 폴백 단계. 단계별 적중률을 측정한다.
   *
   * <p>LLM_MAIN 폴백은 SLO에서 사실상 발생하면 안 되는 케이스라 enum에서 제외했다. 경량 LLM 분류가 실패하면 안전 기본값(SKIP 등)으로 폴백하는 것이
   * 더 적절.
   */
  public enum Tier {
    /** Pre-route 룰 매처가 결정. 가장 싸고 결정론적. */
    RULE,
    /** 경량 LLM 분류기(Grok/Haiku 등)가 결정. 미술 의도 등 룰이 못 잡는 케이스. */
    LLM_LIGHT
  }

  /** 슬롯 없는 단순 케이스용 팩토리. */
  public static IntentResult of(IntentCode code, Tier tier) {
    return new IntentResult(code, List.of(), false, tier);
  }

  public boolean hasReferencedImages() {
    return !referencedImages.isEmpty();
  }
}
