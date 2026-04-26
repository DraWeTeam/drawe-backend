package com.drawe.backend.domain.llm.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.ALWAYS)
public record ChatResponse(
    String sessionId,
    String type,
    String message,
    List<ReferenceItem> references,
    String followUp) {

  public record ReferenceItem(
      Long id, String url, String technique, String subject, String mood, Double similarity) {}
}
