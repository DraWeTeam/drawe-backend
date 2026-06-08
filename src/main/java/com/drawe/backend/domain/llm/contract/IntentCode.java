package com.drawe.backend.domain.llm.contract;

/**
 * 사용자 메시지의 의도를 분류하는 코드.
 *
 * <p>강사님 피드백 #5 (case 코드화) + 외부 리뷰 보강 후 확정.
 * 자세한 결정 근거: {@code docs/decisions/AI-pipeline-review-decisions.md} §5
 *
 * <p>현재(S0) 상태: enum 정의만 존재. 실제 런타임 분기는 베타 종료 후 Phase 1에서 도입.
 * 베타 중에는 기존 {@link com.drawe.backend.domain.llm.dto.ExtractionResult.Action} 이 분기를 담당.
 *
 * <p>{@code ExtractionResult.Action} 과의 매핑 (베타 후 마이그레이션 가이드):
 * <ul>
 *   <li>{@code NEW_SEARCH}    → {@link #NEW_SEARCH}</li>
 *   <li>{@code KEEP}          → {@link #KEEP}</li>
 *   <li>{@code SKIP}          → {@link #SKIP}</li>
 *   <li>{@code GENERATE_NOW}  → {@link #GENERATE}</li>
 * </ul>
 * 나머지 코드(001~004, 010~013, 000)는 베타 후 신규 도입.
 *
 * <p>이전 plan에 있던 {@code 009} (N번 이미지 참조)는 의도가 아니라 파라미터로 취급하기로 결정,
 * enum에서 제거하고 별도 앵커 슬롯({@link IntentResult#referencedImages})으로 분리한다.
 */
public enum IntentCode {

  /** 도메인 외 질문 (음식·날씨·잡담 등 비미술). 거절 응답. */
  OUT_OF_DOMAIN("000"),

  /** 구도 분석. */
  COMPOSITION("001"),

  /** 빛/명암 분석. */
  LIGHTING("002"),

  /** 색감/색상 조언. */
  COLOR("003"),

  /** 기법 질문 (수채화/유화/디지털 등). */
  TECHNIQUE("004"),

  /** 새 레퍼런스 요청. 키워드 추출 → 검색 수행. */
  NEW_SEARCH("005"),

  /** 기존 레퍼런스 유지 / 같은 레퍼런스에 대한 세부 질문. */
  KEEP("006"),

  /** 잡담/감사. 짧은 응답, 레퍼런스 없음. */
  SKIP("007"),

  /** AI 이미지 생성 요청. PromptTranslator → Bria 호출. */
  GENERATE("008"),

  // 009 (N번 이미지 참조) — 의도가 아니라 파라미터. IntentResult.referencedImages 슬롯으로 분리.

  /** 사용자 본인 작업물 비평 (이미지 업로드 + 평가 요청). 멀티모달 입력 경로. */
  SELF_CRITIQUE("010"),

  /** 학습 경로 / 커리큘럼 코칭 ("초보자는 뭐부터?"). */
  LEARNING_PATH("011"),

  /** 직전 답변에 대한 부연·후속 질문. KEEP과 달리 레퍼런스 유지가 아님. */
  FOLLOWUP("012"),

  /** 비교 (두 레퍼런스 / 내 시안 vs 레퍼런스). */
  COMPARE("013");

  private final String code;

  IntentCode(String code) {
    this.code = code;
  }

  public String code() {
    return code;
  }
}
