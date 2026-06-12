package com.drawe.backend.domain.llm.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TokenAwareHistoryTrimmerTest {

  private TokenAwareHistoryTrimmer trimmer;
  private TokenCounter counter;
  private TokenBudgetConfig budget;
  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    counter = new TokenCounter();
    HistorySanitizer sanitizer = new HistorySanitizer();
    NoopTopicChangeDetector detector = new NoopTopicChangeDetector();
    budget = new TokenBudgetConfig();
    registry = new SimpleMeterRegistry();

    trimmer = new TokenAwareHistoryTrimmer(counter, sanitizer, detector, budget, registry);
    trimmer.init();
  }

  @Test
  @DisplayName("빈 입력은 빈 리스트")
  void emptyInputReturnsEmpty() {
    assertThat(trimmer.trim(null, "msg")).isEmpty();
    assertThat(trimmer.trim(List.of(), "msg")).isEmpty();
  }

  @Test
  @DisplayName("예산 안의 작은 history 는 그대로 보존")
  void smallHistoryPreserved() {
    List<LlmCallContext.Turn> turns =
        List.of(
            new LlmCallContext.Turn(MessageRole.SYSTEM, "페르소나"),
            new LlmCallContext.Turn(MessageRole.USER, "안녕"),
            new LlmCallContext.Turn(MessageRole.ASSISTANT, "안녕하세요"));

    List<LlmCallContext.Turn> result = trimmer.trim(turns, "오늘 뭐할까");

    assertThat(result).hasSize(3);
  }

  @Test
  @DisplayName("SYSTEM 먼저, USER·ASSISTANT 나중 순서 유지")
  void preservesOrderSystemFirst() {
    List<LlmCallContext.Turn> turns =
        List.of(
            new LlmCallContext.Turn(MessageRole.USER, "u1"),
            new LlmCallContext.Turn(MessageRole.SYSTEM, "sys1"),
            new LlmCallContext.Turn(MessageRole.ASSISTANT, "a1"),
            new LlmCallContext.Turn(MessageRole.SYSTEM, "sys2"));

    List<LlmCallContext.Turn> result = trimmer.trim(turns, "현재");

    // SYSTEM 들이 먼저 나옴
    assertThat(result.get(0).role()).isEqualTo(MessageRole.SYSTEM);
    assertThat(result.get(1).role()).isEqualTo(MessageRole.SYSTEM);
    // 그 다음 USER·ASSISTANT
    assertThat(result.get(2).role()).isIn(MessageRole.USER, MessageRole.ASSISTANT);
  }

  @Test
  @DisplayName("History 예산 초과 시 오래된 것부터 제거")
  void trimsOldestFirst() {
    budget.setHistoryBudget(50); // 매우 작은 예산

    List<LlmCallContext.Turn> turns = new ArrayList<>();
    for (int i = 0; i < 20; i++) {
      turns.add(new LlmCallContext.Turn(MessageRole.USER, "메시지 번호 " + i));
    }

    List<LlmCallContext.Turn> result = trimmer.trim(turns, "현재");

    // 일부만 남음
    assertThat(result.size()).isLessThan(20);

    // 가장 최근 메시지는 포함됨
    String lastContent = result.get(result.size() - 1).content();
    assertThat(lastContent).contains("19");
  }

  @Test
  @DisplayName("SYSTEM 예산 초과 시 일부만 포함")
  void trimsSystemToBudget() {
    budget.setSystemBudget(20); // 매우 작은 예산

    List<LlmCallContext.Turn> turns = new ArrayList<>();
    for (int i = 0; i < 10; i++) {
      turns.add(
          new LlmCallContext.Turn(MessageRole.SYSTEM, "system message number " + i + " content"));
    }

    List<LlmCallContext.Turn> result = trimmer.trim(turns, "msg");

    long systemCount =
        result.stream().filter(t -> t.role() == MessageRole.SYSTEM).count();
    assertThat(systemCount).isLessThan(10);
  }

  @Test
  @DisplayName("[N] 마커는 USER·ASSISTANT 에서 제거됨")
  void referencesStripped() {
    List<LlmCallContext.Turn> turns =
        List.of(new LlmCallContext.Turn(MessageRole.ASSISTANT, "[1]번 이미지처럼"));

    List<LlmCallContext.Turn> result = trimmer.trim(turns, "현재");

    assertThat(result).hasSize(1);
    assertThat(result.get(0).content()).doesNotContain("[1]");
  }

  @Test
  @DisplayName("입력 토큰 메트릭 기록됨")
  void recordsInputTokensMetric() {
    List<LlmCallContext.Turn> turns =
        List.of(new LlmCallContext.Turn(MessageRole.USER, "안녕"));

    trimmer.trim(turns, "현재 메시지");

    double count = registry.get("drawe.tokens.input").summary().count();
    assertThat(count).isPositive();
  }

  @Test
  @DisplayName("Trim 카운터 메트릭 — 예산 초과 시 증가")
  void recordsTrimmedMetric() {
    budget.setHistoryBudget(20);

    List<LlmCallContext.Turn> turns = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      turns.add(new LlmCallContext.Turn(MessageRole.USER, "긴 메시지 " + i));
    }

    trimmer.trim(turns, "현재");

    double count = registry.get("drawe.history.trimmed").counter().count();
    assertThat(count).isPositive();
  }
}
