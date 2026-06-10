package com.drawe.backend.domain.llm.session;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

/**
 * 프로젝트 단위 휘발성 대화 컨텍스트 (Redis 저장).
 *
 * <p>S2' Phase 6 — 단기 메모리. 멀티턴 효율을 위해 직전 검색 결과·의도·키워드를 빠르게 lookup
 * 가능한 위치에 저장.
 *
 * <p>저장 위치: Redis ({@code session:{userId}:{projectId}})
 * <p>TTL: 24시간 (활동 시 갱신)
 *
 * <p>장기 메모리 (영구) 는 MySQL 의 {@code ChatSession} + {@code LlmMessage}. Redis cache miss
 * 시 {@link SessionService#getOrRestore} 가 MySQL 에서 복원.
 */
public record SessionData(
        Long userId,
        Long projectId,

        /** 직전 검색 결과 — KEEP 의도 시 SYSTEM 블록으로 주입 (멀티턴 핵심). */
        List<ReferenceImage> previousReferences,

        /** 직전 의도 — 후속 의도 분류 정확도 보조. MySQL 복원 시에는 null. */
        IntentCode lastIntent,

        /** 직전 추출 키워드 — 디버깅·메트릭. */
        List<String> lastKeywords,

        /** 마지막 활동 시각. */
        Instant lastUpdatedAt) {

  @JsonCreator
  public SessionData(
      @JsonProperty("userId") Long userId,
      @JsonProperty("projectId") Long projectId,
      @JsonProperty("previousReferences") List<ReferenceImage> previousReferences,
      @JsonProperty("lastIntent") IntentCode lastIntent,
      @JsonProperty("lastKeywords") List<String> lastKeywords,
      @JsonProperty("lastUpdatedAt") Instant lastUpdatedAt) {
    this.userId = userId;
    this.projectId = projectId;
    this.previousReferences = previousReferences == null ? List.of() : previousReferences;
    this.lastIntent = lastIntent;
    this.lastKeywords = lastKeywords == null ? List.of() : lastKeywords;
    this.lastUpdatedAt = lastUpdatedAt == null ? Instant.now() : lastUpdatedAt;
  }

  /** 빈 세션 시작 (메시지 없는 새 프로젝트). */
  public static SessionData start(Long userId, Long projectId) {
    return new SessionData(userId, projectId, List.of(), null, List.of(), Instant.now());
  }

  /** 새 검색 결과로 갱신 (NEW_SEARCH 후). */
  public SessionData withSearchResult(
      IntentCode intent, List<String> keywords, List<ReferenceImage> references) {
    return new SessionData(userId, projectId, references, intent, keywords, Instant.now());
  }

  /** KEEP 의도 — references 는 유지, 의도만 갱신. */
  public SessionData withKeep(IntentCode intent) {
    return new SessionData(
        userId, projectId, previousReferences, intent, lastKeywords, Instant.now());
  }

  /** SKIP/GENERATE 등 — references 유지, 의도·시각 갱신. */
  public SessionData withIntent(IntentCode intent) {
    return new SessionData(
        userId, projectId, previousReferences, intent, lastKeywords, Instant.now());
  }
}
