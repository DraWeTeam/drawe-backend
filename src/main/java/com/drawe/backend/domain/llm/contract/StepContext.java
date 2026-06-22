package com.drawe.backend.domain.llm.contract;

import com.drawe.backend.domain.llm.dto.GenerateImageResponse;
import java.util.List;
import lombok.With;

/**
 * 파이프라인 컨텍스트 — step 간 상태 전달.
 *
 * <p><strong>불변(record) + {@code @With}</strong>: Lombok 이 {@code withKeywords(...)}, {@code
 * withReferences(...)} 등 wither 자동 생성. 각 step 은 기존 ctx 를 수정하지 않고 새 ctx 를 반환.
 *
 * <h2>필드 분류</h2>
 *
 * <ul>
 *   <li><strong>입력 (A가 채움, B는 읽기만)</strong>: {@code userId}, {@code projectId}, {@code sessionId},
 *       {@code rawMessage}, {@code cleanedMessage}, {@code intent}, {@code uploadedImageUrl},
 *       {@code previousReferences}
 *   <li><strong>누적 (B가 채움)</strong>: {@code keywords}, {@code references}
 *   <li><strong>누적 (A가 채움)</strong>: {@code generatedImage}, {@code composedAnswer}
 * </ul>
 *
 * <h2>cleanedMessage 정규화 규칙 (#1 답변 박제)</h2>
 *
 * <p>A의 TextPreprocessor 가 적용:
 *
 * <table border="1">
 *   <tr><th>적용</th><th>안 함</th></tr>
 *   <tr><td>{@code trim()}</td>
 *       <td>소문자화 (한글 무관, 영문은 Komoran이 처리)</td></tr>
 *   <tr><td>연속 공백 → 단일 공백</td>
 *       <td>오타교정 (별도 단계, 현재 범위 밖)</td></tr>
 *   <tr><td>{@code \[?\d+\]?번} 제거 → 숫자는 {@code referencedImages} 슬롯으로</td>
 *       <td>형태소/품사 필터링 (B의 Komoran 책임)</td></tr>
 * </table>
 *
 * <p>예시: <br>
 * 입력: {@code " 벚꽃 [2]번처럼 더 보여줘 "} <br>
 * → {@code cleanedMessage}: {@code "벚꽃 처럼 더 보여줘"} <br>
 * → {@code referencedImages}: {@code [2]}
 */
@With
public record StepContext(
    // ── 입력 (A가 채움, B는 읽기만) ──
    Long userId,
    Long projectId,
    String sessionId,

    // 원문 — 디버깅용. PII 주의.
    String rawMessage,
    // 정규화된 메시지 — B의 Komoran 입력.
    String cleanedMessage,
    IntentResult intent,
    // 010 SELF_CRITIQUE 용. 없으면 null.
    String uploadedImageUrl,
    // 006 KEEP 용. 직전 검색 결과.
    List<ReferenceImage> previousReferences,

    // ── 누적 (B가 채움) ──
    List<String> keywords,
    List<ReferenceImage> references,

    // ── 누적 (A가 채움) ──
    GenerateImageResponse generatedImage,
    String composedAnswer) {

  /**
   * 새 파이프라인 시작 — A의 분류·전처리 결과로 초기 ctx 생성. 누적 필드(keywords, references, generatedImage,
   * composedAnswer) 는 null 로 시작.
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
        null,
        null, // keywords, references
        null,
        null // generatedImage, composedAnswer
        );
  }
}
