package com.drawe.backend.domain.llm.context;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 히스토리 메시지의 reference 마커 정제.
 *
 * <p>S2' Phase 6 Layer 3. 옛 검색의 [1][2][3] 과 새 검색의 [1][2][3] 이 LLM 컨텍스트 안에서 혼선되는 hallucination 차단.
 *
 * <p>현재 검색 결과를 가리키는 [N] 마커는 SYSTEM 블록 ([참고 이미지]) 에서만 유효하게 유지하고, USER·ASSISTANT 메시지의 [N] 마커는 모두
 * 제거한다. SYSTEM 메시지는 그대로 보존.
 *
 * <p>예:
 *
 * <ul>
 *   <li>입력 ASSISTANT: "[1]번 이미지처럼 부드러운 색감을 표현해보세요"
 *   <li>출력 ASSISTANT: "번 이미지처럼 부드러운 색감을 표현해보세요"
 * </ul>
 *
 * <p>제거된 "번" 같은 부수 단어는 자연스러운 문장으로 남아 LLM 의 의미 이해엔 영향 적다. 핵심은 옛 [N] 숫자가 새 [N] 인덱스와 충돌하지 않게 하는 것.
 */
@Component
public class HistorySanitizer {

  /** [N] 패턴 — N 은 1자리 이상 숫자. */
  private static final Pattern REF_MARKER = Pattern.compile("\\[(\\d+)\\]");

  /** 연속 공백 정리. */
  private static final Pattern MULTI_SPACE = Pattern.compile("\\s+");

  /**
   * Turn 목록 전체 정제. SYSTEM 은 보존, USER·ASSISTANT 는 [N] 제거.
   *
   * @param turns 원본 Turn 목록
   * @return 정제된 Turn 목록 (원본 size 유지)
   */
  public List<LlmCallContext.Turn> sanitize(List<LlmCallContext.Turn> turns) {
    if (turns == null) {
      return List.of();
    }
    return turns.stream().map(this::sanitizeTurn).toList();
  }

  private LlmCallContext.Turn sanitizeTurn(LlmCallContext.Turn turn) {
    if (turn == null || turn.role() == MessageRole.SYSTEM) {
      return turn;
    }
    return new LlmCallContext.Turn(turn.role(), stripReferences(turn.content()));
  }

  /** 단일 문자열에서 [N] 마커 제거 + 공백 정리. */
  public String stripReferences(String content) {
    if (content == null || content.isEmpty()) {
      return content;
    }
    String stripped = REF_MARKER.matcher(content).replaceAll("");
    return MULTI_SPACE.matcher(stripped).replaceAll(" ").trim();
  }
}
