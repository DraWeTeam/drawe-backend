package com.drawe.backend.domain.llm.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenCounterTest {

  private TokenCounter counter;

  @BeforeEach
  void setUp() {
    counter = new TokenCounter();
  }

  @Test
  @DisplayName("null·빈 문자열은 0 토큰")
  void countEmptyOrNull() {
    assertThat(counter.count(null)).isZero();
    assertThat(counter.count("")).isZero();
  }

  @Test
  @DisplayName("영어 짧은 문장 토큰 카운팅")
  void countShortEnglish() {
    // "hello world" → cl100k_base 에서 ~2 토큰
    assertThat(counter.count("hello world")).isBetween(1, 3);
  }

  @Test
  @DisplayName("한국어 문장 토큰 카운팅")
  void countKorean() {
    // 한국어는 영어보다 토큰 효율 낮음 (대략 1글자 ~2-3토큰)
    String text = "안녕하세요";
    int tokens = counter.count(text);
    assertThat(tokens).isPositive();
    assertThat(tokens).isLessThanOrEqualTo(15); // 5글자 × 3토큰 한도
  }

  @Test
  @DisplayName("긴 문장은 더 많은 토큰")
  void countLongerHasMoreTokens() {
    int shortTokens = counter.count("hi");
    int longTokens = counter.count("this is a much longer sentence with many words");
    assertThat(longTokens).isGreaterThan(shortTokens);
  }

  @Test
  @DisplayName("null Turn 은 0 토큰")
  void countTurnNull() {
    assertThat(counter.countTurn(null)).isZero();
  }

  @Test
  @DisplayName("Turn 은 role overhead 포함")
  void countTurnIncludesOverhead() {
    LlmCallContext.Turn turn = new LlmCallContext.Turn(MessageRole.USER, "hello");
    int turnTokens = counter.countTurn(turn);
    int textTokens = counter.count("hello");
    // role overhead = 4
    assertThat(turnTokens).isEqualTo(textTokens + 4);
  }

  @Test
  @DisplayName("Turns 목록 합산")
  void countTurnsList() {
    List<LlmCallContext.Turn> turns =
        List.of(
            new LlmCallContext.Turn(MessageRole.SYSTEM, "system prompt"),
            new LlmCallContext.Turn(MessageRole.USER, "hello"),
            new LlmCallContext.Turn(MessageRole.ASSISTANT, "hi there"));

    int total = counter.countTurns(turns);
    int sum = turns.stream().mapToInt(counter::countTurn).sum();
    assertThat(total).isEqualTo(sum);
  }

  @Test
  @DisplayName("빈 Turns 목록은 0")
  void countTurnsEmpty() {
    assertThat(counter.countTurns(null)).isZero();
    assertThat(counter.countTurns(List.of())).isZero();
  }
}
