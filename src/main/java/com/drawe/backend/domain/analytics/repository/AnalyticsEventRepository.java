package com.drawe.backend.domain.analytics.repository;

import com.drawe.backend.domain.AnalyticsEvent;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalyticsEventRepository extends JpaRepository<AnalyticsEvent, Long> {

  List<AnalyticsEvent> findBySessionIdOrderByCreatedAtAsc(String sessionId);

  List<AnalyticsEvent> findByUserIdAndCreatedAtAfterOrderByCreatedAtAsc(Long userId, Instant after);

  long countByEventTypeAndCreatedAtAfter(String eventType, Instant after);
}
