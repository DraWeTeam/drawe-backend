package com.drawe.backend.domain.llm.dto;

import com.drawe.backend.domain.llm.contract.IntentCode;

/**
 * 검색/생성 의도 분류 결과.
 *
 * @param action 기능 분기 (검색/유지/스킵/생성)
 * @param keywords NEW_SEARCH 면 영문 검색 키워드, GENERATE_NOW 면 생성 프롬프트(또는 한국어 원문). 그 외 null.
 * @param artIntent KEEP 일 때 미술 의도 세분류 (001 구도/002 빛/003 색/004 기법). 분류 불가·미분류 또는 KEEP 이 아니면 null. 트랙
 *     A ②-2차에서 KEEP 을 IntentCode 001~004 로 세분화하기 위한 슬롯. 어댑터가 이 값으로 IntentResult.code 를 정한다(null 이면
 *     006 KEEP 유지).
 */
public record ExtractionResult(Action action, String keywords, IntentCode artIntent) {
  public enum Action {
    NEW_SEARCH,
    KEEP,
    SKIP,
    /** 사용자가 명시적으로 이미지 생성을 요청. 검색을 건너뛰고 즉시 Bria 호출. */
    GENERATE_NOW
  }

  public static ExtractionResult newSearch(String keywords) {
    return new ExtractionResult(Action.NEW_SEARCH, keywords, null);
  }

  /** 미분류 KEEP (미술 의도 라벨 없음 → 어댑터에서 006 유지). */
  public static ExtractionResult keep() {
    return new ExtractionResult(Action.KEEP, null, null);
  }

  /**
   * 미술 의도가 세분류된 KEEP. {@code artIntent} 는 001 COMPOSITION / 002 LIGHTING / 003 COLOR / 004
   * TECHNIQUE 중 하나여야 한다.
   */
  public static ExtractionResult keep(IntentCode artIntent) {
    return new ExtractionResult(Action.KEEP, null, artIntent);
  }

  public static ExtractionResult skip() {
    return new ExtractionResult(Action.SKIP, null, null);
  }

  /** keywords 에는 생성에 쓸 영문 프롬프트(또는 시드 한국어)를 담는다. */
  public static ExtractionResult generateNow(String prompt) {
    return new ExtractionResult(Action.GENERATE_NOW, prompt, null);
  }
}
