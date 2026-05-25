package com.drawe.backend.domain.analytics;

/**
 * Analytics 이벤트 타입 상수.
 *
 * <p>오타 방지 + 추적 가능한 이벤트 목록 명시화. 새 이벤트 추가 시 여기에 상수 먼저 추가.
 */
public final class AnalyticsEventType {

  private AnalyticsEventType() {}

  // ── 채팅 ──────────────────────────────────────────
  /** 새 채팅 세션 시작 (createSessionWithPersona 호출 시). */
  public static final String CHAT_START = "chat_start";

  /** 채팅 응답 성공 완료. payload: latency_ms, response_length, provider. */
  public static final String CHAT_SUCCESS = "chat_success";

  /** 채팅 에러. payload: error_class, error_msg. */
  public static final String CHAT_ERROR = "chat_error";

  // ── 검색 ──────────────────────────────────────────
  /** 검색 실행 (NEW_SEARCH 결정 후). payload: keyword, result_count, avg/max/min score. */
  public static final String SEARCH_EXECUTED = "search_executed";

  /** 검색 결과 무관 판단으로 차단. payload: keyword, avg_score, max_score. */
  public static final String SEARCH_BLOCKED = "search_blocked";

  /** 키워드 추출 결정: 이전 references 유지. */
  public static final String DECISION_KEEP = "decision_keep";

  /** 키워드 추출 결정: 검색 불필요. */
  public static final String DECISION_SKIP = "decision_skip";

  // ── 온보딩 ────────────────────────────────────────
  /** 온보딩 완료. payload: selected_count, saved_pref_count. */
  public static final String ONBOARDING_COMPLETED = "onboarding_completed";

  // ── 가이드 ────────────────────────────────────────
  /** LLM 가이드 응답 완성 (chat_success와 같이 발송, 가이드 품질 분석용). */
  public static final String GUIDE_COMPLETED = "guide_completed";
}
