package com.drawe.backend.domain.analytics.service;

import com.drawe.backend.domain.AnalyticsEvent;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.analytics.repository.AnalyticsEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
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
 *   <li>analytics_events 테이블 — DBeaver/SQL로 사후 분석 (원본 payload 유지, 접근 권한 통제)
 *   <li>로그 (CloudWatch) — 실시간 모니터링 (사용자 의도 파생 필드는 길이로 마스킹)
 * </ul>
 *
 * <p>이벤트 저장 실패는 비즈니스 로직에 영향 X. 에러만 로깅하고 계속 진행.
 *
 * <p><b>트랜잭션 분리</b>: 모든 public 오버로드에 {@code @Transactional(REQUIRES_NEW)} 적용. 호출 측 트랜잭션 롤백 시에도 이벤트는
 * 별도 트랜잭션으로 저장됨. Spring AOP proxy 기반이므로 자기호출(self-invocation) 방지를 위해 실제 작업은 private {@link
 * #doTrack}에 위임.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalyticsEventService {

  /**
   * 로그에 박지 않을 payload 키 — 사용자 메시지/의도에서 파생된 값들.
   *
   * <p>DB에는 그대로 저장 (분석용). 로그엔 {@code <key>_length} 로 치환되어 박힘.
   */
  private static final Set<String> SENSITIVE_PAYLOAD_KEYS = Set.of("keyword");

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
    doTrack(eventType, userId, sessionId, payload);
  }

  /** payload 없는 단순 이벤트용 오버로드. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void track(String eventType, Long userId, String sessionId) {
    doTrack(eventType, userId, sessionId, Map.of());
  }

  /** User 객체 받는 편의 오버로드. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void track(String eventType, User user, String sessionId, Map<String, Object> payload) {
    doTrack(eventType, user != null ? user.getId() : null, sessionId, payload);
  }

  /** User 객체 + payload 없는 오버로드. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void track(String eventType, User user, String sessionId) {
    doTrack(eventType, user != null ? user.getId() : null, sessionId, Map.of());
  }

  /**
   * 실제 저장 로직. 모든 public {@code track(...)} 오버로드가 위임.
   *
   * <p>private이므로 직접 호출 불가 — 외부에서는 트랜잭션이 적용된 public 메서드만 진입 가능.
   *
   * <p>이 메서드 자체는 {@code @Transactional} 없음. 호출 측 (public track 메서드)이 시작한 REQUIRES_NEW 트랜잭션에 참여한다.
   */
  private void doTrack(
      String eventType, Long userId, String sessionId, Map<String, Object> payload) {
    try {
      AnalyticsEvent event = new AnalyticsEvent();
      event.setEventType(eventType);
      event.setUserId(userId);
      event.setSessionId(sessionId);
      event.setPayloadJson(serializePayload(payload)); // DB: 원본 payload (분석용)
      eventRepository.save(event);

      // CloudWatch 로그: 사용자 의도 파생 필드는 길이로 마스킹
      log.info(
          "📊 event_type={}, user_id={}, session_id={}, payload={}",
          eventType,
          userId,
          sessionId,
          sanitizePayloadForLog(payload));
    } catch (Exception e) {
      log.warn(
          "Analytics event 저장 실패: type={}, error_class={}",
          eventType,
          e.getClass().getSimpleName());
    }
  }

  /**
   * 로그용 payload 정제.
   *
   * <p>{@link #SENSITIVE_PAYLOAD_KEYS}에 해당하는 키는 값을 제거하고 {@code <key>_length}로 치환. DB에 저장되는 원본
   * payload엔 영향 없음.
   *
   * <p>예: {@code {keyword: "watercolor portrait"}} → {@code {keyword_length: 19}}
   */
  private Map<String, Object> sanitizePayloadForLog(Map<String, Object> payload) {
    if (payload == null || payload.isEmpty()) {
      return Map.of();
    }
    Map<String, Object> safe = new HashMap<>(payload);
    for (String key : SENSITIVE_PAYLOAD_KEYS) {
      Object value = safe.remove(key);
      if (value instanceof String s) {
        safe.put(key + "_length", s.length());
      } else if (value != null) {
        safe.put(key + "_present", true);
      }
    }
    return safe;
  }

  private String serializePayload(Map<String, Object> payload) {
    try {
      return objectMapper.writeValueAsString(payload != null ? payload : Map.of());
    } catch (JsonProcessingException e) {
      log.warn("Payload 직렬화 실패: error_class={}", e.getClass().getSimpleName());
      return "{}";
    }
  }
}
