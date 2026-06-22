package com.drawe.backend.domain.llm.context;

import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.ModelType;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * JTokkit 기반 토큰 카운터.
 *
 * <p>S2' Phase 6 Layer 1. GPT-4 인코딩 (cl100k_base) 사용 — Claude·Grok 도 비슷한 BPE 토크나이저를 쓰므로 정확하지는 않아도
 * 운영 모니터링·예산 통제에 충분.
 *
 * <p>한국어는 영어보다 토큰 효율이 낮은 편이지만 (한 글자 ~2-3 토큰), 동일 인코더로 측정하면 상대 비교는 정확하다.
 */
@Component
public class TokenCounter {

  /** 메시지당 role 오버헤드 (대략적인 OpenAI 가이드: 4 토큰). */
  private static final int ROLE_OVERHEAD = 4;

  private final Encoding encoding;

  public TokenCounter() {
    EncodingRegistry registry = Encodings.newDefaultEncodingRegistry();
    this.encoding = registry.getEncodingForModel(ModelType.GPT_4);
  }

  /** 텍스트의 토큰 수. null/빈 문자열은 0. */
  public int count(String text) {
    if (text == null || text.isEmpty()) {
      return 0;
    }
    return encoding.countTokens(text);
  }

  /** 단일 Turn 의 토큰 수 (role overhead 포함). */
  public int countTurn(LlmCallContext.Turn turn) {
    if (turn == null) {
      return 0;
    }
    return count(turn.content()) + ROLE_OVERHEAD;
  }

  /** Turn 목록의 토큰 수 합산. */
  public int countTurns(List<LlmCallContext.Turn> turns) {
    if (turns == null || turns.isEmpty()) {
      return 0;
    }
    return turns.stream().mapToInt(this::countTurn).sum();
  }
}
