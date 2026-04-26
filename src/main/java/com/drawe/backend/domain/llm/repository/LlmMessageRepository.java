package com.drawe.backend.domain.llm.repository;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.LlmMessage;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LlmMessageRepository extends JpaRepository<LlmMessage, Long> {

  List<LlmMessage> findByChatSessionOrderByCreatedAtAsc(ChatSession chatSession);
}
