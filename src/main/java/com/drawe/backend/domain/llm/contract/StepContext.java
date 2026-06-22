package com.drawe.backend.domain.llm.contract;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.llm.dto.GenerateImageResponse;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.output.ComposedOutput;
import java.util.List;
import lombok.With;

/**
 * 파이프라인 전체가 거쳐가는 불변 컨텍스트. 각 {@link StepExecutor} 는 이 record 를 받아 일부 필드를 채워 새 record 를 반환한다 ({@code
 * with*} wither 사용).
 *
 * <p>핵심 계약 — A·B 가 가장 자주 읽고 쓰는 타입이므로 필드 변경 시 양쪽 합의 필수.
 *
 * <h3>필드 분류</h3>
 *
 * <ul>
 *   <li><b>입력</b> (A 의 pre-route/분류가 채움, B 는 읽기만): userId, projectId, sessionId, rawMessage,
 *       cleanedMessage, intent, uploadedImageUrl, previousReferences
 *   <li><b>누적</b> (B 가 채움): keywords, references
 *   <li><b>입력</b> (A 의 분류 단계가 채움, COMPOSE 가 읽음 — S2'): history, uploadedImageBytes,
 *       uploadedImageMimeType, provider
 *   <li><b>누적</b> (A 가 채움): generatedImage, composedAnswer, composedOutput, composeModel,
 *       composeLatencyMs
 * </ul>
 *
 * <h3>composedOutput vs composedAnswer (S2' 트랙 A ④)</h3>
 *
 * {@code composedOutput} 이 COMPOSE 합성의 진실의 원천 — 정정된 본문·citations·offerGenerate 를 모두 담는다. {@code
 * composedAnswer} 는 {@code composedOutput.message()} 에서 파생한 본문 한 칸으로, 저장/응답이 String 만 필요할 때를 위한 편의
 * 필드다. {@code ComposeExecutor} 가 둘을 함께 채운다 — 다운스트림(⑤ ChatResponse 조립)이 citations·offerGenerate 까지
 * 꺼내 쓰려면 {@code composedOutput} 을, 본문만 쓰려면 {@code composedAnswer} 를 읽는다.
 *
 * <h3>S2' 추가 필드 (트랙 A — COMPOSE 멀티콜)</h3>
 *
 * {@code ComposeExecutor} 가 LLM 합성을 떠안으려면 분류 단계만 알던 정보가 필요하다 — persona/userPrefs/projectContext
 * SYSTEM turn 이 포함된 누적 {@code history}, 멀티모달 {@code uploadedImageBytes}/{@code
 * uploadedImageMimeType}, 그리고 어느 LLM 으로 부를지 {@code provider}. 전부 record 맨 끝에 추가했고 nullable 이다 — 기존
 * 8-인자 {@link #start} 팩토리는 그대로 보존되어 이 필드들을 {@code null} 로 채우므로 트랙 B 의 생성 지점은 영향받지 않는다(설계: {@code
 * docs/decisions/S2A-output-contract-design.md} §3.1).
 *
 * <h3>cleanedMessage 정규화 규칙 (A 합의안)</h3>
 *
 * <ol>
 *   <li>{@code trim()} + 연속 공백을 단일 공백으로 압축
 *   <li>앵커 패턴 {@code \[?\d+\]?번} 제거 → 제거된 숫자는 {@link IntentResult#referencedImages} 슬롯으로
 *   <li>소문자화 안 함 (한글 무관, 영문은 형태소 분석기가 처리)
 *   <li>오타교정 안 함 (별도 단계, 현재 범위 밖)
 * </ol>
 *
 * 예: {@code " 벚꽃 [2]번처럼 더 보여줘 "} → cleanedMessage: {@code "벚꽃 처럼 더 보여줘"}, referencedImages: {@code
 * [2]}
 *
 * <h3>불변 + 누적 패턴</h3>
 *
 * Lombok {@code @With} 가 {@code withKeywords(...)}, {@code withReferences(...)} 등을 자동 생성한다. 각
 * Executor 는 {@code return ctx.withKeywords(kw);} 형태로 반환.
 */
@With
public record StepContext(
    // ── 입력 ──
    Long userId,
    Long projectId,
    String sessionId,
    String rawMessage,
    String cleanedMessage,
    IntentResult intent,
    String uploadedImageUrl,
    List<ReferenceImage> previousReferences,

    // ── 누적: B ──
    List<String> keywords,
    List<ReferenceImage> references,
    // SEARCH 점수통계·차단판정 (live 갭). SearchExecutor 가 채우고 chatViaWorkflow 가 analytics 발사에 씀.
    SearchStats searchStats,

    // ── 누적: A ──
    GenerateImageResponse generatedImage,
    String composedAnswer,
    ComposedOutput composedOutput,

    // ── 입력: A 분류 단계가 채움, COMPOSE 가 읽음 (S2') ──
    List<LlmCallContext.Turn> history,
    byte[] uploadedImageBytes,
    String uploadedImageMimeType,
    LlmProvider provider,

    // ── 누적: A 의 COMPOSE 가 채우는 LLM 콜 메타 (S2' ⑤) ──
    // composedOutput 은 파싱·무결성 검사를 거친 "정정된 합성 결과"라 LLM 콜 트랜스포트 메타(model·latency)를
    // 담기엔 결이 다르다(파서·체커는 이 값을 알지도 못한다). 그래서 진실의 원천 옆에 별도 슬롯으로 둔다 —
    // ComposeExecutor 가 LlmCallResult 에서 그대로 옮겨 담고, ⑤ 메인경로 매핑이 assistantMsg.model·latencyMs·
    // llmMetrics.llmCall 로 흘려보낸다. 레거시 경로의 result.model()/result.latencyMs() 와 동치.
    String composeModel,
    Integer composeLatencyMs) {

  public StepContext {
    previousReferences = previousReferences == null ? List.of() : List.copyOf(previousReferences);
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
    references = references == null ? List.of() : List.copyOf(references);
    history = history == null ? List.of() : List.copyOf(history);
  }

  /**
   * 파이프라인 시작 컨텍스트 (기본 — COMPOSE 정보 없음). 누적 필드는 빈 값, S2' COMPOSE 입력 필드는 {@code null}.
   *
   * <p>이 8-인자 시그니처는 트랙 B 의 생성 지점(shadow·테스트)이 의존하므로 보존한다. COMPOSE 를 실연결하는 메인 경로는 아래 {@link
   * #startForCompose} 를 쓴다.
   */
  public static StepContext start(
      Long userId,
      Long projectId,
      String sessionId,
      String rawMessage,
      String cleanedMessage,
      IntentResult intent,
      String uploadedImageUrl,
      List<ReferenceImage> previousReferences) {
    return new StepContext(
        userId,
        projectId,
        sessionId,
        rawMessage,
        cleanedMessage,
        intent,
        uploadedImageUrl,
        previousReferences,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        List.of(),
        null,
        null,
        null,
        null,
        null);
  }

  /**
   * COMPOSE 멀티콜용 시작 컨텍스트 (S2'). 분류 단계가 history·이미지·provider 까지 실어 보낸다. {@code ComposeExecutor} 가 이
   * 정보로 LLM 합성을 수행한다.
   */
  public static StepContext startForCompose(
      Long userId,
      Long projectId,
      String sessionId,
      String rawMessage,
      String cleanedMessage,
      IntentResult intent,
      String uploadedImageUrl,
      List<ReferenceImage> previousReferences,
      List<LlmCallContext.Turn> history,
      byte[] uploadedImageBytes,
      String uploadedImageMimeType,
      LlmProvider provider) {
    return new StepContext(
        userId,
        projectId,
        sessionId,
        rawMessage,
        cleanedMessage,
        intent,
        uploadedImageUrl,
        previousReferences,
        List.of(),
        List.of(),
        null,
        null,
        null,
        null,
        history,
        uploadedImageBytes,
        uploadedImageMimeType,
        provider,
        null,
        null);
  }
}
