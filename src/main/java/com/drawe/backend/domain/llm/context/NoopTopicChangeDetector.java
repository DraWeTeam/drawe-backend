package com.drawe.backend.domain.llm.context;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 주제 전환 감지 안 함 — 항상 false 반환하는 Noop 구현.
 *
 * <p>S2' Phase 6 Layer 4 의 v1 기본 구현. 의도 분류 (KeywordExtractor / IntentClassifier) 가 이미 topic change
 * 의 약 90% 를 처리하므로 베타 단계에는 Noop 으로 충분.
 *
 * <p>v2 에서 임베딩 기반 구현 (예: {@code EmbeddingTopicChangeDetector}) 을 활성화하려면:
 *
 * <ul>
 *   <li>실구현체에 {@code @Primary} 어노테이션 추가, 또는
 *   <li>본 클래스에 {@code @ConditionalOnProperty(name="drawe.topic-detection.embedding-enabled",
 *       havingValue="false", matchIfMissing=true)} 추가 후 application.properties 로 토글
 * </ul>
 */
@Slf4j
@Component
public class NoopTopicChangeDetector implements TopicChangeDetector {

  @Override
  public boolean isTopicChange(String previousUserMessage, String currentUserMessage) {
    return false;
  }
}
