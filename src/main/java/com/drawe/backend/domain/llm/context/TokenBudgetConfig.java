package com.drawe.backend.domain.llm.context;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 컨텍스트 토큰 예산 설정.
 *
 * <p>S2' Phase 6 Layer 1. 메시지 개수가 아닌 토큰 단위 예산으로 컨텍스트 trim.
 *
 * <p>application.yml 예시:
 *
 * <pre>{@code
 * drawe:
 *   llm:
 *     context:
 *       system-budget: 1200
 *       history-budget: 4000
 *       current-budget: 1500
 *       total-budget: 8000
 * }</pre>
 *
 * <p>목적: 비용 통제, "lost in the middle" 회피, context overflow 방지, 가시성 (메트릭).
 */
@Configuration
@ConfigurationProperties(prefix = "drawe.llm.context")
public class TokenBudgetConfig {

  /** SYSTEM 메시지 토큰 예산 — 페르소나·선호·프로젝트·참고이미지 합산 한도. */
  private int systemBudget = 1200;

  /** History (USER·ASSISTANT) 토큰 예산. */
  private int historyBudget = 4000;

  /** 새 user 메시지 + LLM 응답 예약 토큰. */
  private int currentBudget = 1500;

  /** 전체 컨텍스트 hard cap (LLM context window 의 안전 마진 포함). */
  private int totalBudget = 8000;

  public int getSystemBudget() {
    return systemBudget;
  }

  public void setSystemBudget(int systemBudget) {
    this.systemBudget = systemBudget;
  }

  public int getHistoryBudget() {
    return historyBudget;
  }

  public void setHistoryBudget(int historyBudget) {
    this.historyBudget = historyBudget;
  }

  public int getCurrentBudget() {
    return currentBudget;
  }

  public void setCurrentBudget(int currentBudget) {
    this.currentBudget = currentBudget;
  }

  public int getTotalBudget() {
    return totalBudget;
  }

  public void setTotalBudget(int totalBudget) {
    this.totalBudget = totalBudget;
  }
}
