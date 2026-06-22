package com.drawe.backend.domain.llm.contract;

import com.drawe.backend.domain.llm.dto.GenerateImageResponse;
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
 *   <li><b>누적</b> (A 가 채움): generatedImage, composedAnswer
 * </ul>
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

    // ── 누적: A ──
    GenerateImageResponse generatedImage,
    String composedAnswer) {

  public StepContext {
    previousReferences = previousReferences == null ? List.of() : List.copyOf(previousReferences);
    keywords = keywords == null ? List.of() : List.copyOf(keywords);
    references = references == null ? List.of() : List.copyOf(references);
  }

  /** 파이프라인 시작 컨텍스트. 누적 필드는 빈 값으로 초기화. */
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
        null);
  }
}
