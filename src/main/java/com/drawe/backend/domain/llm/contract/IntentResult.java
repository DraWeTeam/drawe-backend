package com.drawe.backend.domain.llm.contract;

import java.util.List;

/**
 * 의도 분류 결과.
 *
 * <p>{@link IntentCode} + 슬롯({@code referencedImages}, {@code hasUploadedImage}) + 메트릭 태그({@code
 * tier}).
 *
 * <p>설계 결정:
 *
 * <ul>
 *   <li>{@code Tier.LLM_MAIN} 없음 — 메인 LLM 분류 폴백은 SLO상 0이어야 하며, 발생 시 안전 기본값({@link IntentCode#SKIP})
 *       처리.
 *   <li>{@code referencedImages} 는 [N]번 앵커 슬롯 — IntentCode 009 (참조) 대체.
 *   <li>{@code hasUploadedImage} 는 {@link IntentCode#SELF_CRITIQUE} (010) 트리거.
 * </ul>
 *
 * @param code 분류된 의도 코드
 * @param referencedImages 사용자가 [1], [2] 같이 참조한 이미지 인덱스 (1-based)
 * @param hasUploadedImage 사용자가 이미지를 업로드했는지 (010 트리거)
 * @param tier 분류 단계 (메트릭 태그용)
 */
public record IntentResult(
    IntentCode code, List<Integer> referencedImages, boolean hasUploadedImage, Tier tier) {

  /**
   * 분류 단계.
   *
   * <ul>
   *   <li>{@link #RULE} — 키워드 매처로 즉시 결정 (대다수)
   *   <li>{@link #LLM_LIGHT} — 경량 LLM 분류 폴백 (룰 미스 시)
   * </ul>
   */
  public enum Tier {
    RULE,
    LLM_LIGHT
  }

  /** 앵커·업로드 슬롯 없이 단순 분류 결과 생성. */
  public static IntentResult of(IntentCode code, Tier tier) {
    return new IntentResult(code, List.of(), false, tier);
  }

  /** 앵커 참조가 있는지 빠른 확인. */
  public boolean hasReferencedImages() {
    return referencedImages != null && !referencedImages.isEmpty();
  }
}
