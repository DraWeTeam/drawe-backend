package com.drawe.backend.domain.llm.context;

/**
 * 사용자 메시지 간 주제 전환 감지.
 *
 * <p>S2' Phase 6 Layer 4. v1 에선 {@link NoopTopicChangeDetector} 기본 구현만 등록되어 항상
 * false 반환. 베타 운영 후 hallucination 신고 패턴 분석하여 임베딩 기반 구현 도입 여부 결정.
 *
 * <p>주제 전환의 1차 감지는 의도 분류 (KeywordExtractor / IntentClassifier) 가 NEW_SEARCH / KEEP
 * 분류로 처리한다. 본 detector 는 그 외 edge case 보완용. 예: 의도는 KEEP 인데 실제로는 다른
 * 그림 작업을 시작한 경우.
 *
 * <p>활성화 시 호출 흐름:
 * <ol>
 *   <li>{@link TokenAwareHistoryTrimmer} 가 직전 user 메시지와 현재 메시지 비교</li>
 *   <li>{@code true} 면 history 를 더 공격적으로 trim (최근 2-3턴만)</li>
 * </ol>
 */
public interface TopicChangeDetector {

  /**
   * 두 메시지의 주제가 전환되었는지 판단.
   *
   * @param previousUserMessage 직전 user 메시지 (없으면 null 허용)
   * @param currentUserMessage  현재 user 메시지
   * @return 주제 전환이면 true. v1 Noop 구현에선 항상 false.
   */
  boolean isTopicChange(String previousUserMessage, String currentUserMessage);
}
