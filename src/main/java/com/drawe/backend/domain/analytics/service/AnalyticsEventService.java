package com.drawe.backend.domain.analytics.service;

import com.drawe.backend.domain.AnalyticsEvent;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.analytics.repository.AnalyticsEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 사용자 행동 이벤트 추적.
 *
 * <p>한 번의 track() 호출이 두 군데로 데이터 전송:
 *
 * <ul>
 *   <li>analytics_events 테이블 — DBeaver/SQL로 사후 분석
 *   <li>로그 (CloudWatch) — 실시간 모니터링
 * </ul>
 *
 * <p>이벤트 저장 실패는 비즈니스 로직에 영향 X. 에러만 로깅하고 계속 진행.
 *
 * <p>트랜잭션은 REQUIRES_NEW로 분리 — 호출 측 트랜잭션 롤백 시에도 이벤트는 남도록.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsEventService {

  private final AnalyticsEventRepository eventRepository;
  private final ObjectMapper objectMapper;

  /**
   * 이벤트 추적 - DB + 로그 동시.
   *
   * @param eventType 이벤트 타입 (AnalyticsEventType 상수 사용 권장)
   * @param userId 사용자 ID (없으면 null)
   * @param sessionId 세션 ID (없으면 null)
   * @param payload 이벤트 상세 데이터. null이면 빈 객체로 저장.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void track(String eventType, Long userId, String sessionId, Map<String, Object> payload) {
    try {
      AnalyticsEvent event = new AnalyticsEvent();
      event.setEventType(eventType);
      event.setUserId(userId);
      event.setSessionId(sessionId);
      event.setPayloadJson(serializePayload(payload));
      eventRepository.save(event);

      log.info(
          "📊 event_type={}, user_id={}, session_id={}, payload={}",
          eventType,
          userId,
          sessionId,
          payload != null ? payload : "{}");
    } catch (Exception e) {
      // 분석 이벤트 실패가 비즈니스를 중단시키지 않게.
      log.warn("Analytics event 저장 실패: type={}, error={}", eventType, e.getMessage());
    }
  }

  /** payload 없는 단순 이벤트용 오버로드. */
  public void track(String eventType, Long userId, String sessionId) {
    track(eventType, userId, sessionId, Map.of());
  }

  /** User 객체 받는 편의 오버로드. */
  public void track(String eventType, User user, String sessionId, Map<String, Object> payload) {
    track(eventType, user != null ? user.getId() : null, sessionId, payload);
  }

  /** User 객체 + payload 없는 오버로드. */
  public void track(String eventType, User user, String sessionId) {
    track(eventType, user != null ? user.getId() : null, sessionId, Map.of());
  }

  private String serializePayload(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
    } catch (JsonProcessingException e) {
      log.warn("Payload 직렬화 실패", e);
      return "{}";
    }
  }
}
