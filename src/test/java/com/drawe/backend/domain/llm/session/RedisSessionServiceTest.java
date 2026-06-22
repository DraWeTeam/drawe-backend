package com.drawe.backend.domain.llm.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.dto.ChatResponse;
import com.drawe.backend.domain.llm.repository.LlmMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class RedisSessionServiceTest {

  private RedisSessionService service;
  private StringRedisTemplate redisTemplate;
  private ValueOperations<String, String> valueOps;
  private LlmMessageRepository llmMessageRepository;
  private ObjectMapper objectMapper;
  private SimpleMeterRegistry registry;

  @BeforeEach
  @SuppressWarnings("unchecked")
  void setUp() {
    redisTemplate = mock(StringRedisTemplate.class);
    valueOps = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(valueOps);

    llmMessageRepository = mock(LlmMessageRepository.class);
    objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    registry = new SimpleMeterRegistry();

    service = new RedisSessionService(redisTemplate, objectMapper, llmMessageRepository, registry);
    service.init();
  }

  // ─────────────────────────────────────────────────────
  // Cache hit
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Cache hit — Redis 에서 즉시 반환 + TTL 갱신")
  void cacheHitReturnsAndRefreshes() throws Exception {
    SessionData stored =
        new SessionData(
            42L,
            7L,
            List.of(
                new ReferenceImage(
                    123L,
                    1,
                    "url1",
                    "photographer",
                    java.math.BigDecimal.valueOf(0.85),
                    List.of("수채화"))),
            IntentCode.NEW_SEARCH,
            List.of("watercolor"),
            Instant.now());

    String json = objectMapper.writeValueAsString(stored);
    when(valueOps.get("session:42:7")).thenReturn(json);

    SessionData result = service.getOrRestore(42L, 7L, mock(ChatSession.class));

    assertThat(result.userId()).isEqualTo(42L);
    assertThat(result.projectId()).isEqualTo(7L);
    assertThat(result.previousReferences()).hasSize(1);
    assertThat(result.lastIntent()).isEqualTo(IntentCode.NEW_SEARCH);

    // TTL 갱신 호출됨
    verify(redisTemplate, times(1)).expire(eq("session:42:7"), any(Duration.class));

    // MySQL 폴백 호출 안 됨
    verify(llmMessageRepository, never())
        .findFirstByChatSessionAndRoleOrderByCreatedAtDesc(any(), any());

    // 메트릭
    assertThat(registry.get("drawe.session.cache.hit").counter().count()).isEqualTo(1.0);
  }

  // ─────────────────────────────────────────────────────
  // Cache miss + MySQL 복원
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Cache miss — MySQL 에서 직전 ASSISTANT references 복원")
  void cacheMissRestoresFromMysql() {
    when(valueOps.get("session:42:7")).thenReturn(null);

    LlmMessage lastAssistant = new LlmMessage();
    lastAssistant.setReferences(
        List.of(
            new ChatResponse.ReferenceItem(
                123L, "url1", "photographer", "username", "수채화", "벚꽃", "부드러움", 0.85, "UNSPLASH"),
            new ChatResponse.ReferenceItem(
                456L, "url2", "photographer2", "username2", "잉크", "벚꽃", "강렬함", 0.72, "UNSPLASH")));
    lastAssistant.setCreatedAt(Instant.now().minusSeconds(3600));

    ChatSession session = mock(ChatSession.class);
    when(llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT))
        .thenReturn(Optional.of(lastAssistant));

    SessionData result = service.getOrRestore(42L, 7L, session);

    // references 복원
    assertThat(result.previousReferences()).hasSize(2);
    assertThat(result.previousReferences().get(0).imageId()).isEqualTo(123L);
    assertThat(result.previousReferences().get(0).index()).isEqualTo(1); // 1-based
    assertThat(result.previousReferences().get(1).imageId()).isEqualTo(456L);
    assertThat(result.previousReferences().get(1).index()).isEqualTo(2);

    // tags 합산 (technique + subject + mood)
    assertThat(result.previousReferences().get(0).tags()).contains("수채화", "벚꽃", "부드러움");

    // lastIntent 는 복원 시 null
    assertThat(result.lastIntent()).isNull();

    // Redis 재저장 호출됨
    verify(valueOps, times(1)).set(eq("session:42:7"), any(String.class), any(Duration.class));

    // 메트릭
    assertThat(registry.get("drawe.session.cache.miss").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("drawe.session.cache.restored").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Cache miss + MySQL 메시지 없음 — 빈 SessionData 반환")
  void cacheMissNoMessagesReturnsEmpty() {
    when(valueOps.get("session:42:7")).thenReturn(null);

    ChatSession session = mock(ChatSession.class);
    when(llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT))
        .thenReturn(Optional.empty());

    SessionData result = service.getOrRestore(42L, 7L, session);

    assertThat(result.userId()).isEqualTo(42L);
    assertThat(result.projectId()).isEqualTo(7L);
    assertThat(result.previousReferences()).isEmpty();
    assertThat(result.lastIntent()).isNull();

    // Redis 재저장 호출 안 됨 (빈 데이터 굳이 저장 X)
    verify(valueOps, never()).set(any(), any(), any(Duration.class));

    assertThat(registry.get("drawe.session.cache.miss").counter().count()).isEqualTo(1.0);
    assertThat(registry.get("drawe.session.cache.restored").counter().count()).isZero();
  }

  @Test
  @DisplayName("Cache miss + ChatSession null — 빈 SessionData (MySQL 폴백 안 함)")
  void cacheMissNullSessionReturnsEmpty() {
    when(valueOps.get("session:42:7")).thenReturn(null);

    SessionData result = service.getOrRestore(42L, 7L, null);

    assertThat(result.previousReferences()).isEmpty();

    // MySQL 호출 안 됨
    verify(llmMessageRepository, never())
        .findFirstByChatSessionAndRoleOrderByCreatedAtDesc(any(), any());
  }

  // ─────────────────────────────────────────────────────
  // 저장
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("save — Redis 에 직렬화·저장 + TTL 설정")
  void saveSerializesAndStores() {
    SessionData data = SessionData.start(42L, 7L);

    service.save(data);

    verify(valueOps, times(1)).set(eq("session:42:7"), any(String.class), any(Duration.class));
    assertThat(registry.get("drawe.session.cache.save").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("save — null 입력은 무시")
  void saveNullIgnored() {
    service.save(null);

    verify(valueOps, never()).set(any(), any(), any(Duration.class));
  }

  // ─────────────────────────────────────────────────────
  // 삭제
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("clear — Redis 키 삭제")
  void clearDeletesKey() {
    service.clear(42L, 7L);

    verify(redisTemplate, times(1)).delete("session:42:7");
  }

  // ─────────────────────────────────────────────────────
  // 깨진 데이터 처리
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("깨진 JSON — 자동 정리 + MySQL 폴백")
  void corruptedJsonCleansUpAndFallsBack() {
    when(valueOps.get("session:42:7")).thenReturn("not valid json {{{");

    ChatSession session = mock(ChatSession.class);
    when(llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT))
        .thenReturn(Optional.empty());

    SessionData result = service.getOrRestore(42L, 7L, session);

    // 깨진 키 삭제됨
    verify(redisTemplate, times(1)).delete("session:42:7");

    // 빈 데이터 반환
    assertThat(result.previousReferences()).isEmpty();
  }

  // ─────────────────────────────────────────────────────
  // TTL 정책 값 (24h) — any(Duration) 가 아니라 실제 값 검증
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("save — TTL 은 정확히 24시간으로 설정")
  void saveUsesTwentyFourHourTtl() {
    service.save(SessionData.start(42L, 7L));

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(valueOps).set(eq("session:42:7"), any(String.class), ttl.capture());

    assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(24));
  }

  @Test
  @DisplayName("Cache hit — TTL 갱신도 정확히 24시간")
  void cacheHitRefreshesWithTwentyFourHourTtl() throws Exception {
    SessionData stored = SessionData.start(42L, 7L);
    when(valueOps.get("session:42:7")).thenReturn(objectMapper.writeValueAsString(stored));

    service.getOrRestore(42L, 7L, mock(ChatSession.class));

    ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
    verify(redisTemplate).expire(eq("session:42:7"), ttl.capture());

    assertThat(ttl.getValue()).isEqualTo(Duration.ofHours(24));
  }

  // ─────────────────────────────────────────────────────
  // Redis 연결 자체 실패 (예외) → MySQL 폴백
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("Redis read 예외 — 폴백으로 MySQL 복원 시도 (장애 격리)")
  void redisReadExceptionFallsBackToMysql() {
    when(valueOps.get("session:42:7"))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));

    ChatSession session = mock(ChatSession.class);
    when(llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT))
        .thenReturn(Optional.empty());

    SessionData result = service.getOrRestore(42L, 7L, session);

    // 예외를 삼키고 정상 흐름 (빈 세션) 으로 진행 — 요청이 죽지 않음
    assertThat(result.previousReferences()).isEmpty();
    // MySQL 폴백 시도됨
    verify(llmMessageRepository, times(1))
        .findFirstByChatSessionAndRoleOrderByCreatedAtDesc(session, MessageRole.ASSISTANT);
    // miss 로 집계
    assertThat(registry.get("drawe.session.cache.miss").counter().count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Redis 다운 + MySQL 복원 — 재저장(save) 실패해도 요청은 살아남음")
  void redisDownDuringRestoreDoesNotKillRequest() {
    // read 는 예외 (Redis 다운)
    when(valueOps.get("session:42:7"))
        .thenThrow(new org.springframework.dao.QueryTimeoutException("redis down"));
    // 복원할 MySQL 메시지는 있음 → miss 경로에서 save() 호출됨
    LlmMessage lastAssistant = new LlmMessage();
    lastAssistant.setReferences(
        List.of(
            new ChatResponse.ReferenceItem(
                123L, "url1", "photographer", "username", "수채화", "벚꽃", "부드러움", 0.85, "UNSPLASH")));
    lastAssistant.setCreatedAt(Instant.now());

    ChatSession session = mock(ChatSession.class);
    when(llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT))
        .thenReturn(Optional.of(lastAssistant));
    // 재저장도 Redis 다운으로 실패 (set 은 void → doThrow)
    org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("redis down"))
        .when(valueOps)
        .set(any(), any(), any(Duration.class));

    // 예외 전파 없이 복원된 데이터 반환
    SessionData result = service.getOrRestore(42L, 7L, session);

    assertThat(result.previousReferences()).hasSize(1);
    assertThat(result.previousReferences().get(0).imageId()).isEqualTo(123L);
    // save 시도는 했음
    verify(valueOps, times(1)).set(eq("session:42:7"), any(String.class), any(Duration.class));
  }

  @Test
  @DisplayName("save — Redis I/O 장애 시 예외 전파 없이 로깅만 (요청 보호)")
  void saveRedisIoFailureDoesNotThrow() {
    org.mockito.Mockito.doThrow(new org.springframework.dao.QueryTimeoutException("redis down"))
        .when(valueOps)
        .set(any(), any(), any(Duration.class));

    // 예외 없이 반환
    service.save(SessionData.start(42L, 7L));

    // save 카운터는 증가하지 않음 (set 이 던졌으므로)
    assertThat(registry.get("drawe.session.cache.save").counter().count()).isZero();
  }

  @Test
  @DisplayName("save 직렬화 실패 — 예외 전파 없이 로깅만 (요청 보호)")
  void saveSerializeFailureDoesNotThrow() {
    ObjectMapper failing = mock(ObjectMapper.class);
    try {
      when(failing.writeValueAsString(any()))
          .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});
    } catch (Exception ignored) {
      // mock stubbing
    }
    RedisSessionService svc =
        new RedisSessionService(redisTemplate, failing, llmMessageRepository, registry);
    svc.init();

    // 예외 없이 반환
    svc.save(SessionData.start(42L, 7L));

    // Redis 저장은 호출되지 않음
    verify(valueOps, never()).set(any(), any(), any(Duration.class));
  }

  // ─────────────────────────────────────────────────────
  // 복원 시 null similarity → BigDecimal.ZERO (NPE 방어)
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("복원 — null similarity 는 BigDecimal.ZERO 로 변환 (NPE 방어)")
  void restoreNullSimilarityBecomesZero() {
    when(valueOps.get("session:42:7")).thenReturn(null);

    LlmMessage lastAssistant = new LlmMessage();
    lastAssistant.setReferences(
        List.of(
            new ChatResponse.ReferenceItem(
                123L,
                "url1",
                "photographer",
                "username",
                "수채화",
                "벚꽃",
                "부드러움",
                null /* similarity */,
                "UNSPLASH")));
    lastAssistant.setCreatedAt(Instant.now());

    ChatSession session = mock(ChatSession.class);
    when(llmMessageRepository.findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
            session, MessageRole.ASSISTANT))
        .thenReturn(Optional.of(lastAssistant));

    SessionData result = service.getOrRestore(42L, 7L, session);

    assertThat(result.previousReferences()).hasSize(1);
    assertThat(result.previousReferences().get(0).score())
        .isEqualByComparingTo(java.math.BigDecimal.ZERO);
  }
}
