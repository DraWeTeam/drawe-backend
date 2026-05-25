package com.drawe.backend.domain.llm.dto;

public record ExtractionResult(Action action, String keywords) {
  public enum Action {
    NEW_SEARCH,
    KEEP,
    SKIP,
    /** 사용자가 명시적으로 이미지 생성을 요청. 검색을 건너뛰고 즉시 Bria 호출. */
    GENERATE_NOW
  }

  public static ExtractionResult newSearch(String keywords) {
    return new ExtractionResult(Action.NEW_SEARCH, keywords);
  }

  public static ExtractionResult keep() {
    return new ExtractionResult(Action.KEEP, null);
  }

  public static ExtractionResult skip() {
    return new ExtractionResult(Action.SKIP, null);
  }

  /** keywords 에는 생성에 쓸 영문 프롬프트(또는 시드 한국어)를 담는다. */
  public static ExtractionResult generateNow(String prompt) {
    return new ExtractionResult(Action.GENERATE_NOW, prompt);
  }
}
