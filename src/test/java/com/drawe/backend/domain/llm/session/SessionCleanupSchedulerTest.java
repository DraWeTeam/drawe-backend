package com.drawe.backend.domain.llm.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.llm.repository.ChatSessionRepository;
import com.drawe.backend.domain.llm.repository.LlmMessageRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

class SessionCleanupSchedulerTest {

  private SessionCleanupScheduler scheduler;
  private ChatSessionRepository chatSessionRepository;
  private LlmMessageRepository llmMessageRepository;
  private SimpleMeterRegistry registry;

  @BeforeEach
  void setUp() {
    chatSessionRepository = Mockito.mock(ChatSessionRepository.class);
    llmMessageRepository = Mockito.mock(LlmMessageRepository.class);
    registry = new SimpleMeterRegistry();

    scheduler = new SessionCleanupScheduler(chatSessionRepository, llmMessageRepository, registry);
    ReflectionTestUtils.setField(scheduler, "inactiveThresholdDays", 30);
    scheduler.init();
  }

  @Test
  @DisplayName("청소 대상 없으면 무동작")
  void noStaleSessions() {
    when(chatSessionRepository.findAllByLastActiveBefore(any(Instant.class))).thenReturn(List.of());

    scheduler.cleanupStaleSessions();

    verify(llmMessageRepository, times(0)).deleteByChatSession(any());
    verify(chatSessionRepository, times(0)).delete(any());

    double cleaned = registry.get("drawe.session.cleanup").counter().count();
    assertThat(cleaned).isZero();
  }

  @Test
  @DisplayName("청소 대상 N개 세션 삭제 → 메트릭 N 증가")
  void cleansAllStaleSessions() {
    ChatSession s1 = new ChatSession();
    s1.setId("session-1");
    s1.setLastActive(Instant.now().minus(40, ChronoUnit.DAYS));

    ChatSession s2 = new ChatSession();
    s2.setId("session-2");
    s2.setLastActive(Instant.now().minus(50, ChronoUnit.DAYS));

    when(chatSessionRepository.findAllByLastActiveBefore(any(Instant.class)))
        .thenReturn(List.of(s1, s2));

    scheduler.cleanupStaleSessions();

    verify(llmMessageRepository, times(1)).deleteByChatSession(s1);
    verify(llmMessageRepository, times(1)).deleteByChatSession(s2);
    verify(chatSessionRepository, times(1)).delete(s1);
    verify(chatSessionRepository, times(1)).delete(s2);

    double cleaned = registry.get("drawe.session.cleanup").counter().count();
    assertThat(cleaned).isEqualTo(2.0);
  }

  @Test
  @DisplayName("한 세션 삭제 실패 시 다른 세션은 계속 청소")
  void continuesOnError() {
    ChatSession s1 = new ChatSession();
    s1.setId("session-1");

    ChatSession s2 = new ChatSession();
    s2.setId("session-2");

    when(chatSessionRepository.findAllByLastActiveBefore(any(Instant.class)))
        .thenReturn(List.of(s1, s2));

    doThrow(new RuntimeException("DB error")).when(llmMessageRepository).deleteByChatSession(s1);

    scheduler.cleanupStaleSessions();

    // s2 는 정상 청소됨
    verify(llmMessageRepository, times(1)).deleteByChatSession(s2);
    verify(chatSessionRepository, times(1)).delete(s2);

    // s1 은 메시지 삭제 실패 → session 삭제 안 함
    verify(chatSessionRepository, times(0)).delete(s1);

    // 메트릭은 성공한 1개만
    double cleaned = registry.get("drawe.session.cleanup").counter().count();
    assertThat(cleaned).isEqualTo(1.0);
  }

  @Test
  @DisplayName("cutoff 는 inactiveThresholdDays(=30) 만큼 과거로 계산")
  void cutoffReflectsThreshold() {
    when(chatSessionRepository.findAllByLastActiveBefore(any(Instant.class))).thenReturn(List.of());

    Instant before = Instant.now().minus(30, ChronoUnit.DAYS);
    scheduler.cleanupStaleSessions();
    Instant after = Instant.now().minus(30, ChronoUnit.DAYS);

    ArgumentCaptor<Instant> cutoff = ArgumentCaptor.forClass(Instant.class);
    verify(chatSessionRepository).findAllByLastActiveBefore(cutoff.capture());

    // cutoff ≈ now - 30d (실행 사이 시각 오차 허용)
    assertThat(cutoff.getValue()).isBetween(before.minusSeconds(5), after.plusSeconds(5));
  }
}
