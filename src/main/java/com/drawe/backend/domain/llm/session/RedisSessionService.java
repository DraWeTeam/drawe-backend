package com.drawe.backend.domain.llm.session;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.dto.ChatResponse;
import com.drawe.backend.domain.llm.repository.LlmMessageRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis 기반 {@link SessionService} 구현 + MySQL 폴백.
 *
 * <p>S2' Phase 6 — 단기 메모리. 멀티턴 효율 (KEEP 의도 시 previousReferences 즉시 lookup) 이 주 목적.
 *
 * <p>흐름:
 *
 * <ol>
 *   <li>Redis hit → 즉시 반환, TTL 갱신, {@code drawe.session.cache.hit} 증가
 *   <li>Redis miss → MySQL 의 직전 ASSISTANT 메시지 references 복원, Redis 재저장, {@code
 *       drawe.session.cache.miss}·{@code drawe.session.cache.restored} 증가
 *   <li>MySQL 도 없음 → {@link SessionData#start} 반환 (새 세션)
 * </ol>
 *
 * <p>직렬화: {@link ObjectMapper} (Jackson, JavaTimeModule 필요).
 *
 * <p>의존성:
 *
 * <ul>
 *   <li>{@code spring-boot-starter-data-redis} (build.gradle)
 *   <li>{@code spring.data.redis.*} 설정 (application.yml)
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSessionService implements SessionService {

  private static final Duration TTL = Duration.ofHours(24);
  private static final String KEY_PREFIX = "session:";

  private static final String METRIC_HIT = "drawe.session.cache.hit";
  private static final String METRIC_MISS = "drawe.session.cache.miss";
  private static final String METRIC_RESTORED = "drawe.session.cache.restored";
  private static final String METRIC_SAVE = "drawe.session.cache.save";

  private final StringRedisTemplate redisTemplate;
  private final ObjectMapper objectMapper;
  private final LlmMessageRepository llmMessageRepository;
  private final MeterRegistry meterRegistry;

  private Counter hitCounter;
  private Counter missCounter;
  private Counter restoredCounter;
  private Counter saveCounter;

  @PostConstruct
  public void init() {
    hitCounter = meterRegistry.counter(METRIC_HIT);
    missCounter = meterRegistry.counter(METRIC_MISS);
    restoredCounter = meterRegistry.counter(METRIC_RESTORED);
    saveCounter = meterRegistry.counter(METRIC_SAVE);
  }

  @Override
  public SessionData getOrRestore(Long userId, Long projectId, ChatSession session) {
    String key = key(userId, projectId);

    // 1. Redis 시도 (hot path)
    Optional<SessionData> cached = readFromRedis(key);
    if (cached.isPresent()) {
      redisTemplate.expire(key, TTL); // 활동 = TTL 갱신
      hitCounter.increment();
      return cached.get();
    }

    missCounter.increment();

    // 2. Cache miss → MySQL 폴백
    if (session == null) {
      return SessionData.start(userId, projectId);
    }

    Optional<LlmMessage> lastAssistant =
        llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT);

    if (lastAssistant.isEmpty()) {
      // 메시지 없는 새 세션
      return SessionData.start(userId, projectId);
    }

    // 3. references_json → ReferenceImage 변환
    List<ReferenceImage> restored = convertToReferenceImages(lastAssistant.get().getReferences());

    SessionData data =
        new SessionData(
            userId,
            projectId,
            restored,
            null, // lastIntent — 영구 보관 안 함, 복원 시 null
            List.of(), // lastKeywords — 영구 보관 안 함
            lastAssistant.get().getCreatedAt());

    // 4. Redis 에 재저장 (다음 KEEP 위해)
    save(data);
    restoredCounter.increment();

    log.info(
        "Session restored from MySQL — userId={}, projectId={}, refs={}",
        userId,
        projectId,
        restored.size());

    return data;
  }

  @Override
  public void save(SessionData data) {
    if (data == null) {
      return;
    }
    String key = key(data.userId(), data.projectId());
    try {
      String json = objectMapper.writeValueAsString(data);
      redisTemplate.opsForValue().set(key, json, TTL);
      saveCounter.increment();
    } catch (JsonProcessingException e) {
      log.error(
          "Session serialize failed — key={}, error_class={}", key, e.getClass().getSimpleName());
      // 저장 실패는 로깅만 — 다음 요청에서 MySQL 복원 시도
    } catch (Exception e) {
      // Redis I/O 장애 격리 — read 경로와 동일하게 best-effort. 저장 실패해도 요청은 살린다.
      log.warn(
          "Session save (redis) failed — key={}, error_class={}",
          key,
          e.getClass().getSimpleName());
    }
  }

  @Override
  public void clear(Long userId, Long projectId) {
    redisTemplate.delete(key(userId, projectId));
  }

  // ─────────────────────────────────────────────────────
  // 내부 헬퍼
  // ─────────────────────────────────────────────────────

  private Optional<SessionData> readFromRedis(String key) {
    String json;
    try {
      json = redisTemplate.opsForValue().get(key);
    } catch (Exception e) {
      log.warn("Redis read failed — key={}, error_class={}", key, e.getClass().getSimpleName());
      return Optional.empty();
    }

    if (json == null) {
      return Optional.empty();
    }

    try {
      return Optional.of(objectMapper.readValue(json, SessionData.class));
    } catch (JsonProcessingException e) {
      log.warn(
          "Session deserialize failed — key={}, error_class={}", key, e.getClass().getSimpleName());
      // 깨진 데이터 정리
      try {
        redisTemplate.delete(key);
      } catch (Exception ignored) {
        // best-effort
      }
      return Optional.empty();
    }
  }

  /**
   * MySQL 의 {@link ChatResponse.ReferenceItem} → {@link ReferenceImage} 변환.
   *
   * <p>index 는 1-based, 리스트 순서대로 할당. tags 는 technique·subject·mood 를 합산.
   * photographerUsername·source 는 ReferenceImage 에 없어 누락 (KEEP 의도 SYSTEM 구성엔 불필요).
   */
  private List<ReferenceImage> convertToReferenceImages(List<ChatResponse.ReferenceItem> items) {
    if (items == null || items.isEmpty()) {
      return List.of();
    }

    List<ReferenceImage> result = new ArrayList<>(items.size());
    for (int i = 0; i < items.size(); i++) {
      ChatResponse.ReferenceItem item = items.get(i);
      result.add(
          new ReferenceImage(
              item.id(),
              i + 1, // 1-based index
              item.url(),
              item.photographerName(),
              item.similarity() != null ? BigDecimal.valueOf(item.similarity()) : BigDecimal.ZERO,
              mergeTags(item)));
    }
    return result;
  }

  private List<String> mergeTags(ChatResponse.ReferenceItem item) {
    List<String> tags = new ArrayList<>();
    if (item.technique() != null && !item.technique().isBlank()) {
      tags.add(item.technique());
    }
    if (item.subject() != null && !item.subject().isBlank()) {
      tags.add(item.subject());
    }
    if (item.mood() != null && !item.mood().isBlank()) {
      tags.add(item.mood());
    }
    return tags;
  }

  private static String key(Long userId, Long projectId) {
    return KEY_PREFIX + userId + ":" + projectId;
  }
}
