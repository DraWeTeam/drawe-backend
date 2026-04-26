package com.drawe.backend.domain.llm.dto;

import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.enums.MessageRole;
import java.time.Instant;
import java.util.List;

public record ChatHistoryResponse(String sessionId, List<HistoryItem> messages) {

  public record HistoryItem(String role, String content, Instant createdAt) {
    public static HistoryItem from(LlmMessage m) {
      return new HistoryItem(roleName(m.getRole()), m.getContent(), m.getCreatedAt());
    }

    private static String roleName(MessageRole role) {
      return switch (role) {
        case SYSTEM -> "system";
        case USER -> "user";
        case ASSISTANT -> "assistant";
      };
    }
  }
}
