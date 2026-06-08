package com.drawe.backend.domain.llm.contract;

/**
 * WorkflowService 가 실행하는 step 종류. {@link IntentRouting#ROUTING} 의 값 리스트가 이 enum의 시퀀스다.
 *
 * <p>각 step 은 {@link StepExecutor} 구현체와 1:1 매칭된다. 스프링이 {@code Map<StepType, StepExecutor>} 를 자동 주입한다.
 *
 * <p>소유권:
 * <ul>
 *   <li>A: COMPOSE, TRANSLATE, GENERATE_IMAGE</li>
 *   <li>B: EXTRACT_KEYWORDS, SEARCH</li>
 *   <li>A+B: CRITIQUE_UPLOAD (이미지 임베딩=B, compose=A)</li>
 * </ul>
 *
 * <p>TRANSLATE 의 A 소유 결정 근거: 현재 {@code PromptTranslator} 는 단순 KO→EN 사전 매핑이 아니라
 * 프로젝트 컨텍스트(subject/technique/mood)를 녹여 Bria 이미지 생성 프롬프트를 만드는 정교한 LLM 콜이다.
 * B의 검색용 키워드 사전(짧은 3-6개)과는 목적·복잡도가 달라 분리 유지.
 * 베타 후 B 사전이 충분히 풍부해지면 재검토.
 */
public enum StepType {

  /** B — Komoran 형태소 분석 → 도메인 사전(KO→EN) → 영문 키워드. 사전 미스 시 LLM 폴백. */
  EXTRACT_KEYWORDS,

  /** B — CLIP 임베딩 검색 + Score Guard (avg/max 임계치). 기존 SearchService 래핑. */
  SEARCH,

  /** A — 한국어 이미지 생성 요청 → Bria용 영문 프롬프트로 변환 (LLM 콜, 컨텍스트 반영). */
  TRANSLATE,

  /** A — Bria API 호출로 이미지 생성. */
  GENERATE_IMAGE,

  /** A+B — 사용자 업로드 이미지 비평 (010). 이미지 임베딩(B) → 컨텍스트 합성(A). */
  CRITIQUE_UPLOAD,

  /** A — 페르소나 v2 + Structured Output. 참조 무결성 검사 포함. */
  COMPOSE
}
