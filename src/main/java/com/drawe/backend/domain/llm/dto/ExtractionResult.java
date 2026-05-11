package com.drawe.backend.domain.llm.dto;

public record ExtractionResult(Action action, String keywords) {
  public enum Action {
    NEW_SEARCH,
    KEEP,
    SKIP
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
}
