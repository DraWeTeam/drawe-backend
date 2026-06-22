package com.drawe.backend.domain.llm.context;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NoopTopicChangeDetectorTest {

  private NoopTopicChangeDetector detector;

  @BeforeEach
  void setUp() {
    detector = new NoopTopicChangeDetector();
  }

  @Test
  @DisplayName("어떤 입력이든 항상 false")
  void alwaysReturnsFalse() {
    assertThat(detector.isTopicChange("벚꽃", "고양이")).isFalse();
    assertThat(detector.isTopicChange("수채화 풍경", "이번엔 도시 야경")).isFalse();
    assertThat(detector.isTopicChange("같은 주제", "같은 주제")).isFalse();
  }

  @Test
  @DisplayName("null 안전")
  void safeWithNulls() {
    assertThat(detector.isTopicChange(null, "현재")).isFalse();
    assertThat(detector.isTopicChange("이전", null)).isFalse();
    assertThat(detector.isTopicChange(null, null)).isFalse();
  }

  @Test
  @DisplayName("빈 문자열 안전")
  void safeWithEmptyStrings() {
    assertThat(detector.isTopicChange("", "")).isFalse();
    assertThat(detector.isTopicChange("", "메시지")).isFalse();
  }
}
