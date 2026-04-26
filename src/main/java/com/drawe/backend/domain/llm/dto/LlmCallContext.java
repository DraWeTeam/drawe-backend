package com.drawe.backend.domain.llm.dto;

import com.drawe.backend.domain.enums.MessageRole;
import java.util.List;

/** LLM 구현체에 전달할 호출 컨텍스트(히스토리 + 새 입력). */
public record LlmCallContext(
    List<Turn> history, String newPrompt, byte[] imageBytes, String imageMimeType) {

  public record Turn(MessageRole role, String content) {}
}
