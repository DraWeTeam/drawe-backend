package com.drawe.backend.domain.llm.dto;

import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.image.service.ImageUrlSigner;
import java.time.Instant;
import java.util.List;

public record ChatHistoryResponse(String sessionId, List<HistoryItem> messages) {

  public record HistoryItem(
      String role,
      String content,
      List<ChatResponse.ReferenceItem> references,
      String imageUrl,
      Instant createdAt) {

    /**
     * 저장된 메시지를 응답으로 변환하면서 DB blob 이미지 URL({@code /images/{id}})에 서명을 붙인다. 채팅 히스토리를 다시 그릴 때도 AI
     * 이미지가 브라우저 {@code <img>} 로 로드돼야 하므로 노출 직전 서명이 필요하다. Unsplash 절대 URL 은 signer 가 그대로 통과시킨다.
     */
    public static HistoryItem from(LlmMessage m, ImageUrlSigner signer) {
      return new HistoryItem(
          roleName(m.getRole()),
          m.getContent(),
          signReferences(m.getReferences(), signer),
          signer.sign(m.getImageUrl()),
          m.getCreatedAt());
    }

    private static List<ChatResponse.ReferenceItem> signReferences(
        List<ChatResponse.ReferenceItem> refs, ImageUrlSigner signer) {
      if (refs == null) {
        return null;
      }
      return refs.stream()
          .map(
              r ->
                  new ChatResponse.ReferenceItem(
                      r.id(),
                      signer.sign(r.url()),
                      r.photographerName(),
                      r.photographerUsername(),
                      r.technique(),
                      r.subject(),
                      r.mood(),
                      r.similarity(),
                      r.source()))
          .toList();
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
