package com.drawe.backend.domain.llm.session;

import com.drawe.backend.domain.ChatSession;

/**
 * 단기 메모리 (휘발성 대화 컨텍스트) 관리.
 *
 * <p>S2' Phase 6 — Redis 기반 멀티턴 효율 캐시. 24h TTL 자동 만료, cache miss 시 MySQL ({@code
 * LlmMessage.references_json}) 에서 자동 복원.
 *
 * <p>Cache 정책:
 *
 * <ul>
 *   <li>Hit: Redis 에서 즉시 반환, TTL 갱신
 *   <li>Miss + MySQL 메시지 있음: 직전 ASSISTANT 메시지의 references 로 복원 + Redis 재저장
 *   <li>Miss + 메시지 없음: 빈 SessionData 반환 (새 세션)
 * </ul>
 */
public interface SessionService {

  /**
   * 세션 데이터 조회 또는 복원.
   *
   * <p>핫 경로: Redis hit → 즉시 반환 + TTL 갱신
   *
   * <p>콜드 경로: Redis miss → MySQL 폴백 → Redis 재저장
   *
   * @param userId 사용자 ID
   * @param projectId 프로젝트 ID
   * @param session ChatSession (MySQL 폴백용)
   * @return 세션 데이터 (없으면 {@link SessionData#start})
   */
  SessionData getOrRestore(Long userId, Long projectId, ChatSession session);

  /** 세션 데이터 저장 (TTL 24h). */
  void save(SessionData data);

  /** 명시적 세션 삭제 (사용자 reset 등). */
  void clear(Long userId, Long projectId);
}
