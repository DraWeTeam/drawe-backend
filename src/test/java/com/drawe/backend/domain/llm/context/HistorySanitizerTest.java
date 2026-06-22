package com.drawe.backend.domain.llm.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class HistorySanitizerTest {

  private HistorySanitizer sanitizer;

  @BeforeEach
  void setUp() {
    sanitizer = new HistorySanitizer();
  }

  @Test
  @DisplayName("[N] 마커 제거")
  void stripsSingleMarker() {
    String result = sanitizer.stripReferences("[1]번 이미지처럼 부드러운 색감");
    assertThat(result).doesNotContain("[1]");
    assertThat(result).contains("번 이미지처럼");
  }

  @Test
  @DisplayName("여러 [N] 마커 모두 제거")
  void stripsMultipleMarkers() {
    String result = sanitizer.stripReferences("[1]번과 [2]번, 그리고 [3]번도 좋아요");
    assertThat(result).doesNotContain("[1]").doesNotContain("[2]").doesNotContain("[3]");
  }

  @Test
  @DisplayName("두 자리 이상 [N] 도 제거")
  void stripsMultiDigitMarkers() {
    String result = sanitizer.stripReferences("[10]번도 좋고 [123]번도");
    assertThat(result).doesNotContain("[10]").doesNotContain("[123]");
  }

  @Test
  @DisplayName("[N] 없는 문장은 그대로 (공백 정리 제외)")
  void noMarkersPreservesContent() {
    String original = "부드러운 색감을 표현해보세요";
    String result = sanitizer.stripReferences(original);
    assertThat(result).isEqualTo(original);
  }

  @Test
  @DisplayName("[abc] 같은 비숫자 대괄호는 보존")
  void preservesNonNumericBrackets() {
    String result = sanitizer.stripReferences("[참고 이미지] 안내");
    assertThat(result).contains("[참고 이미지]");
  }

  @Test
  @DisplayName("연속 공백 정리")
  void cleansMultipleSpaces() {
    String result = sanitizer.stripReferences("[1]  많은   공백  [2]");
    assertThat(result).doesNotContain("  "); // 연속 공백 없음
  }

  @Test
  @DisplayName("null·빈 문자열은 그대로")
  void handlesNullOrEmpty() {
    assertThat(sanitizer.stripReferences(null)).isNull();
    assertThat(sanitizer.stripReferences("")).isEmpty();
  }

  @Test
  @DisplayName("SYSTEM 메시지는 [N] 마커 보존")
  void preservesSystemMessages() {
    LlmCallContext.Turn system = new LlmCallContext.Turn(MessageRole.SYSTEM, "[1] 참고 이미지 1번: 수채화");
    List<LlmCallContext.Turn> result = sanitizer.sanitize(List.of(system));
    assertThat(result).hasSize(1);
    assertThat(result.get(0).content()).contains("[1]");
  }

  @Test
  @DisplayName("USER·ASSISTANT 는 [N] 마커 제거")
  void stripsUserAndAssistant() {
    List<LlmCallContext.Turn> turns =
        List.of(
            new LlmCallContext.Turn(MessageRole.USER, "[1]번 이미지 좋네"),
            new LlmCallContext.Turn(MessageRole.ASSISTANT, "네, [1]번은 수채화입니다"));

    List<LlmCallContext.Turn> result = sanitizer.sanitize(turns);

    assertThat(result).hasSize(2);
    assertThat(result.get(0).content()).doesNotContain("[1]");
    assertThat(result.get(1).content()).doesNotContain("[1]");
  }

  @Test
  @DisplayName("null·빈 리스트 안전")
  void handlesNullOrEmptyList() {
    assertThat(sanitizer.sanitize(null)).isEmpty();
    assertThat(sanitizer.sanitize(List.of())).isEmpty();
  }

  @Test
  @DisplayName("role 정보는 보존")
  void preservesRoles() {
    List<LlmCallContext.Turn> turns =
        List.of(
            new LlmCallContext.Turn(MessageRole.USER, "[1]"),
            new LlmCallContext.Turn(MessageRole.ASSISTANT, "[2]"),
            new LlmCallContext.Turn(MessageRole.SYSTEM, "[3]"));

    List<LlmCallContext.Turn> result = sanitizer.sanitize(turns);

    assertThat(result.get(0).role()).isEqualTo(MessageRole.USER);
    assertThat(result.get(1).role()).isEqualTo(MessageRole.ASSISTANT);
    assertThat(result.get(2).role()).isEqualTo(MessageRole.SYSTEM);
  }
}
