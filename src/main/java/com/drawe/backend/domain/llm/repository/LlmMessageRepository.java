package com.drawe.backend.domain.llm.repository;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.enums.MessageRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

public interface LlmMessageRepository extends JpaRepository<LlmMessage, Long> {

  List<LlmMessage> findByChatSessionOrderByCreatedAtAsc(ChatSession chatSession);

  /**
   * 특정 세션의 특정 role 메시지 중 가장 최근 1개.
   *
   * <p>S2' Phase 6 Layer 2 — KEEP 의도에서 previousReferences 채울 때 사용. 직전 ASSISTANT 메시지의 {@code
   * references_json} 컬럼을 가져옴.
   */
  Optional<LlmMessage> findFirstByChatSessionAndRoleOrderByCreatedAtDesc(
      ChatSession chatSession, MessageRole role);

  /**
   * 세션의 모든 메시지 일괄 삭제.
   *
   * <p>S2' Phase 6 Layer 0 SessionCleanupScheduler 가 사용. {@code @Transactional} 컨텍스트 안에서 호출되어야 함.
   */
  @Transactional
  void deleteByChatSession(ChatSession chatSession);
}
