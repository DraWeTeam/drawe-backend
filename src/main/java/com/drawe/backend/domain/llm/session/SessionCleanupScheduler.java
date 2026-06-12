package com.drawe.backend.domain.llm.session;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.llm.repository.ChatSessionRepository;
import com.drawe.backend.domain.llm.repository.LlmMessageRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 장기 미활성 세션 자동 청소 (MySQL).
 *
 * <p>S2' Phase 6 Layer 0. {@link ChatSession#getLastActive()} 기준으로 임계 시간 이전의 세션과
 * 그 세션의 {@code LlmMessage} 들을 일괄 삭제.
 *
 * <p>임계 기본값 90일 — 한 분기 단위. 사용자가 한 달 휴식 후 돌아와도 대화 기록 보존.
 *
 * <p>베타 안전을 위해 기본 비활성. 운영 시 {@code drawe.session.cleanup-enabled=true} 로 활성화.
 *
 * <p>application.yml 예시:
 * <pre>{@code
 * drawe:
 *   session:
 *     cleanup-enabled: false             # 베타 안전 기본 OFF
 *     cleanup-cron: "0 0 4 * * *"        # 매일 새벽 4시
 *     inactive-threshold-days: 90        # 90일 이상 비활성 → 청소
 * }</pre>
 *
 * <p>스프링 부트 메인 클래스에 {@code @EnableScheduling} 이 활성화되어 있어야 한다.
 *
 * <p>관련: 단기 메모리 ({@link RedisSessionService}) 는 24h TTL 자동 만료. 본 스케줄러는 장기
 * (MySQL) 만 담당.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    name = "drawe.session.cleanup-enabled",
    havingValue = "true",
    matchIfMissing = false)
public class SessionCleanupScheduler {

  private static final String METRIC_CLEANED = "drawe.session.cleanup";

  private final ChatSessionRepository chatSessionRepository;
  private final LlmMessageRepository llmMessageRepository;
  private final MeterRegistry meterRegistry;

  @Value("${drawe.session.inactive-threshold-days:90}")
  private int inactiveThresholdDays;

  private Counter cleanedCounter;

  @PostConstruct
  public void init() {
    this.cleanedCounter =
        Counter.builder(METRIC_CLEANED)
            .description("Number of stale chat sessions cleaned up")
            .register(meterRegistry);
    log.info(
        "SessionCleanupScheduler 활성화 — inactiveThresholdDays={}", inactiveThresholdDays);
  }

  /** 매일 새벽 4시 (기본). cron 설정으로 조정 가능. */
  @Scheduled(cron = "${drawe.session.cleanup-cron:0 0 4 * * *}")
  @Transactional
  public void cleanupStaleSessions() {
    Instant cutoff = Instant.now().minus(inactiveThresholdDays, ChronoUnit.DAYS);
    List<ChatSession> stale = chatSessionRepository.findAllByLastActiveBefore(cutoff);

    if (stale.isEmpty()) {
      log.info("Cleanup — 대상 없음 (cutoff={})", cutoff);
      return;
    }

    log.info("Cleanup — {} 개 세션 청소 (cutoff={})", stale.size(), cutoff);

    int cleaned = 0;
    for (ChatSession session : stale) {
      try {
        llmMessageRepository.deleteByChatSession(session);
        chatSessionRepository.delete(session);
        cleaned++;
      } catch (Exception e) {
        log.error(
            "세션 청소 실패 — sessionId={}, error_class={}",
            session.getId(),
            e.getClass().getSimpleName());
      }
    }

    cleanedCounter.increment(cleaned);
    log.info("Cleanup 완료 — 청소된 세션 수: {}", cleaned);
  }
}
