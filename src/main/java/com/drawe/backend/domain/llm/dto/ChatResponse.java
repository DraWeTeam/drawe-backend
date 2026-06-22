package com.drawe.backend.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChatResponse(
    String sessionId,
    String type,
    String message,
    List<ReferenceItem> references,
    String referencesAction,
    boolean offerGenerate,
    String suggestedPrompt,
    // GENERATE_NOW 분기에서 바로 생성된 AI 이미지. 그 외 경우 null.
    GeneratedImage generatedImage) {

  public record ReferenceItem(
      Long id,
      String url,
      String photographerName,
      String photographerUsername,
      String technique,
      String subject,
      String mood,
      Double similarity,
      // ImageSource enum name: "UNSPLASH" | "AI". 프론트는 "AI"일 때 생성 이미지 배지를 렌더한다.
      String source) {}

  /** 사용자의 명시적 "만들어줘" 요청에 응답해 즉시 생성된 이미지. */
  public record GeneratedImage(Long imageId, String url, String prompt) {}
}
