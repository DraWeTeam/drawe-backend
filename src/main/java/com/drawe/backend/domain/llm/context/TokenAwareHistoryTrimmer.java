package com.drawe.backend.domain.llm.context;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 토큰 예산 기반 history trim.
 *
 * <p>S2' Phase 6 Layer 1. 기존 개수 기반 {@code trimHistory(all, maxNonSystem)} 를 대체한다.
 *
 * <p>흐름:
 *
 * <ol>
 *   <li>SYSTEM·USER·ASSISTANT 분리
 *   <li>{@link TopicChangeDetector} 로 주제 전환 감지 (v1 Noop = 항상 false)
 *   <li>SYSTEM 은 등록 순으로 SYSTEM_BUDGET 안에서 포함
 *   <li>USER·ASSISTANT 는 {@link HistorySanitizer} 로 [N] 정제 후 최근부터 역순으로 HISTORY_BUDGET 안에서 포함 (주제 전환
 *       시 1/4 로 더 공격적)
 *   <li>{@code drawe.tokens.input}, {@code drawe.history.trimmed} 메트릭 기록
 * </ol>
 *
 * <p>예시 예산 (기본): SYSTEM 1200 / History 4000 / Current 1500 / Total 8000.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenAwareHistoryTrimmer {

  private static final String METRIC_INPUT_TOKENS = "drawe.tokens.input";
  private static final String METRIC_HISTORY_TRIMMED = "drawe.history.trimmed";

  /** 주제 전환 시 history 예산을 1/4 또는 800 토큰 중 작은 값으로 잘라낸다. */
  private static final int TOPIC_CHANGE_BUDGET_DIVISOR = 4;

  private static final int TOPIC_CHANGE_BUDGET_FLOOR = 800;

  private final TokenCounter tokenCounter;
  private final HistorySanitizer historySanitizer;
  private final TopicChangeDetector topicChangeDetector;
  private final TokenBudgetConfig budget;
  private final MeterRegistry meterRegistry;

  private DistributionSummary inputTokens;
  private Counter trimmedCounter;

  @PostConstruct
  public void init() {
    this.inputTokens =
        DistributionSummary.builder(METRIC_INPUT_TOKENS)
            .description("LLM input tokens per request (system + history + current)")
            .baseUnit("tokens")
            .register(meterRegistry);
    this.trimmedCounter =
        Counter.builder(METRIC_HISTORY_TRIMMED)
            .description("Number of turns trimmed from history due to token budget")
            .register(meterRegistry);
  }

  /**
   * Turn 목록을 토큰 예산 안에 맞게 trim.
   *
   * @param allTurns 전체 메시지 (SYSTEM + USER + ASSISTANT 섞임, 시간 오름차순)
   * @param currentMessage 새 user 메시지 (예산 계산·topic 감지용)
   * @return trim 된 Turn 목록 (SYSTEM 먼저, USER·ASSISTANT 최근 순)
   */
  public List<LlmCallContext.Turn> trim(List<LlmCallContext.Turn> allTurns, String currentMessage) {
    if (allTurns == null || allTurns.isEmpty()) {
      return List.of();
    }

    List<LlmCallContext.Turn> systems = new ArrayList<>();
    List<LlmCallContext.Turn> nonSystems = new ArrayList<>();
    for (LlmCallContext.Turn t : allTurns) {
      if (t.role() == MessageRole.SYSTEM) {
        systems.add(t);
      } else {
        nonSystems.add(t);
      }
    }

    String previousUser = lastUserMessage(nonSystems);
    boolean topicChange = topicChangeDetector.isTopicChange(previousUser, currentMessage);

    List<LlmCallContext.Turn> systemTrimmed =
        trimFromHeadToBudget(systems, budget.getSystemBudget());

    int historyBudget =
        topicChange
            ? Math.min(
                budget.getHistoryBudget() / TOPIC_CHANGE_BUDGET_DIVISOR, TOPIC_CHANGE_BUDGET_FLOOR)
            : budget.getHistoryBudget();

    List<LlmCallContext.Turn> sanitized = historySanitizer.sanitize(nonSystems);
    List<LlmCallContext.Turn> historyTrimmed = trimFromTailToBudget(sanitized, historyBudget);

    int trimmedCount =
        (systems.size() - systemTrimmed.size()) + (nonSystems.size() - historyTrimmed.size());
    if (trimmedCount > 0) {
      trimmedCounter.increment(trimmedCount);
    }

    List<LlmCallContext.Turn> result = new ArrayList<>(systemTrimmed);
    result.addAll(historyTrimmed);

    int totalTokens = tokenCounter.countTurns(result) + tokenCounter.count(currentMessage);
    inputTokens.record(totalTokens);

    log.debug(
        "trim — system={}/{}, history={}/{}, topicChange={}, totalTokens={}",
        systemTrimmed.size(),
        systems.size(),
        historyTrimmed.size(),
        nonSystems.size(),
        topicChange,
        totalTokens);

    return result;
  }

  /** 앞에서부터 채우면서 예산 안에 들어가는 만큼만 포함 (SYSTEM 용 — 등록 순서 보존). */
  private List<LlmCallContext.Turn> trimFromHeadToBudget(
      List<LlmCallContext.Turn> turns, int tokenBudget) {
    List<LlmCallContext.Turn> result = new ArrayList<>();
    int tokens = 0;
    for (LlmCallContext.Turn turn : turns) {
      int turnTokens = tokenCounter.countTurn(turn);
      if (tokens + turnTokens > tokenBudget) {
        break;
      }
      result.add(turn);
      tokens += turnTokens;
    }
    return result;
  }

  /** 최근부터 역순으로 채우면서 예산 안에 들어가는 만큼만 포함 (history 용). */
  private List<LlmCallContext.Turn> trimFromTailToBudget(
      List<LlmCallContext.Turn> turns, int tokenBudget) {
    List<LlmCallContext.Turn> result = new ArrayList<>();
    int tokens = 0;
    for (int i = turns.size() - 1; i >= 0; i--) {
      LlmCallContext.Turn turn = turns.get(i);
      int turnTokens = tokenCounter.countTurn(turn);
      if (tokens + turnTokens > tokenBudget) {
        break;
      }
      result.add(0, turn);
      tokens += turnTokens;
    }
    return result;
  }

  /** 직전 user 메시지 추출 (topic change 비교용). */
  private String lastUserMessage(List<LlmCallContext.Turn> nonSystems) {
    for (int i = nonSystems.size() - 1; i >= 0; i--) {
      LlmCallContext.Turn t = nonSystems.get(i);
      if (t.role() == MessageRole.USER) {
        return t.content();
      }
    }
    return null;
  }
}
