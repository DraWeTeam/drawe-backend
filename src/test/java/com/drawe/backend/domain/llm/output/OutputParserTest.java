package com.drawe.backend.domain.llm.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * OutputParser 단위 테스트(설계 §5·§6.2) — JSON content → ComposedOutput 변환 + 깨진 JSON 안전 폴백.
 *
 * <p>무결성(환각 인용 제거)은 {@link OutputIntegrityCheckerTest} 에서 별도 검증. 여기서는 파싱만.
 */
class OutputParserTest {

  private final OutputParser parser = new OutputParser(new ObjectMapper());

  @Nested
  @DisplayName("정상 JSON 파싱")
  class HappyPath {

    @Test
    @DisplayName("message/citations/offer_generate 를 그대로 옮긴다")
    void parsesAllFields() {
      String json = "{\"message\":\"수채화 기법 [1] 추천해요\",\"citations\":[1,2],\"offer_generate\":true}";

      ComposedOutput out = parser.parse(json);

      assertThat(out.message()).isEqualTo("수채화 기법 [1] 추천해요");
      assertThat(out.citations()).containsExactly(1, 2);
      assertThat(out.offerGenerate()).isTrue();
    }

    @Test
    @DisplayName("빈 citations 배열은 빈 리스트")
    void emptyCitations() {
      ComposedOutput out =
          parser.parse("{\"message\":\"안내\",\"citations\":[],\"offer_generate\":false}");

      assertThat(out.citations()).isEmpty();
      assertThat(out.offerGenerate()).isFalse();
    }

    @Test
    @DisplayName("citations 안의 비정수 원소는 무시(범위 검사는 무결성 단계 몫)")
    void nonIntegerCitationsIgnored() {
      ComposedOutput out =
          parser.parse("{\"message\":\"안내\",\"citations\":[1,\"x\",2.5,3],\"offer_generate\":false}");

      assertThat(out.citations()).containsExactly(1, 3);
    }
  }

  @Nested
  @DisplayName("안전 템플릿 폴백(§6.2) — 원본 노출 금지")
  class Fallback {

    @Test
    @DisplayName("깨진 JSON 이면 안전 템플릿 + 빈 인용")
    void brokenJson() {
      ComposedOutput out = parser.parse("{\"message\": \"잘림...");

      assertThat(out.message()).isEqualTo(OutputParser.BROKEN_JSON_FALLBACK_MESSAGE);
      assertThat(out.citations()).isEmpty();
      assertThat(out.offerGenerate()).isFalse();
    }

    @Test
    @DisplayName("null content 면 폴백")
    void nullContent() {
      assertThat(parser.parse(null).message()).isEqualTo(OutputParser.BROKEN_JSON_FALLBACK_MESSAGE);
    }

    @Test
    @DisplayName("blank content 면 폴백")
    void blankContent() {
      assertThat(parser.parse("   ").message())
          .isEqualTo(OutputParser.BROKEN_JSON_FALLBACK_MESSAGE);
    }

    @Test
    @DisplayName("message 키가 없으면 폴백(원본 JSON 을 본문으로 노출하지 않음)")
    void missingMessage() {
      ComposedOutput out = parser.parse("{\"citations\":[1],\"offer_generate\":true}");

      assertThat(out.message()).isEqualTo(OutputParser.BROKEN_JSON_FALLBACK_MESSAGE);
      assertThat(out.citations()).isEmpty();
    }

    @Test
    @DisplayName("message 가 빈 문자열이면 폴백")
    void blankMessage() {
      assertThat(parser.parse("{\"message\":\"  \",\"citations\":[],\"offer_generate\":false}").message())
          .isEqualTo(OutputParser.BROKEN_JSON_FALLBACK_MESSAGE);
    }
  }
}
