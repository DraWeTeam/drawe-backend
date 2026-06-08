package com.drawe.backend.domain.llm.contract;

/**
 * 파이프라인 step 종류.
 *
 * <p>각 step 은 {@link StepExecutor} 구현체와 1:1 매핑.
 * Spring {@code Map<StepType, StepExecutor>} 자동 주입 키로 사용.
 *
 * <p>소유권:
 * <table border="1">
 *   <tr><th>Step</th><th>담당</th><th>설명</th></tr>
 *   <tr><td>{@link #EXTRACT_KEYWORDS}</td><td>B</td><td>Komoran → 사전 → LLM 폴백</td></tr>
 *   <tr><td>{@link #SEARCH}</td><td>B</td><td>CLIP + Score Guard</td></tr>
 *   <tr><td>{@link #TRANSLATE}</td><td>A</td><td>Grok 프롬프트 변환 (008 GENERATE 용)</td></tr>
 *   <tr><td>{@link #GENERATE_IMAGE}</td><td>A</td><td>Bria 이미지 생성</td></tr>
 *   <tr><td>{@link #CRITIQUE_UPLOAD}</td><td>A+B</td><td>이미지 임베딩(B) + compose(A)</td></tr>
 *   <tr><td>{@link #COMPOSE}</td><td>A</td><td>페르소나 v2 + Structured Output</td></tr>
 * </table>
 *
 * <p>{@code TRANSLATE} 소유 (A) 결정 근거: 현재 PromptTranslator 는 단순 KO→EN
 * 사전 매핑이 아니라 프로젝트 컨텍스트 (subject/technique/mood) 를 녹이고
 * Bria 이미지 생성용 prompt 로 정교하게 변환하는 Grok LLM 콜. B의 검색용
 * 짧은 키워드 사전(3-6개) 과는 목적·복잡도가 다름. 베타 후 B 사전이 풍부해지면 재검토.
 */
public enum StepType {

    /** 한글 메시지 → 영문 키워드 추출 (B). */
    EXTRACT_KEYWORDS,

    /** 키워드 → CLIP 검색 → 레퍼런스 이미지 (B). */
    SEARCH,

    /** 프롬프트 한→영 + 컨텍스트 변환 (A, Grok). */
    TRANSLATE,

    /** Bria 이미지 생성 (A). */
    GENERATE_IMAGE,

    /** 사용자 업로드 이미지 비평 (A+B). */
    CRITIQUE_UPLOAD,

    /** 최종 답변 합성 — 페르소나 v2 + Structured Output (A). */
    COMPOSE
}
