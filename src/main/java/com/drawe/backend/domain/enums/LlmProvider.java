package com.drawe.backend.domain.enums;

import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LlmProvider {
  GROK("grok"),
  GEMINI("gemini");

  private final String code;

  public static LlmProvider fromCode(String code) {
    return Arrays.stream(values())
        .filter(p -> p.code.equalsIgnoreCase(code))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("Unknown LLM provider: " + code));
  }
}
