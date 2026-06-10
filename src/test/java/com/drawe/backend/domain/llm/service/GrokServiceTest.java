package com.drawe.backend.domain.llm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * GrokService 단위 테스트 — Structured Output 매핑(S2' Phase 3 ②)에 집중.
 *
 * <p>HTTP 호출은 통합 범위(③④). 여기서는 순수 함수 {@code responseFormatFor} 의 분기만 검증한다:
 * 스키마 이름 → {@code response_format} 유무 + json_schema/strict 구조.
 */
class GrokServiceTest {

  @Nested
  @DisplayName("responseFormatFor — 평문/구조화 분기")
  class ResponseFormatFor {

    @Test
    @DisplayName("null 이면 response_format 없음(평문)")
    void nullSchema_plain() {
      assertThat(GrokService.responseFormatFor(null)).isNull();
    }

    @Test
    @DisplayName("blank 면 response_format 없음(평문)")
    void blankSchema_plain() {
      assertThat(GrokService.responseFormatFor("   ")).isNull();
    }

    @Test
    @DisplayName("미등록 이름이면 평문 폴백(응답을 깨뜨리지 않음)")
    void unknownSchema_plainFallback() {
      assertThat(GrokService.responseFormatFor("nope_unknown")).isNull();
    }

    @Test
    @DisplayName("draw_guide_response 이면 json_schema + strict + 스키마 동봉")
    @SuppressWarnings("unchecked")
    void drawGuideSchema_structured() {
      Map<String, Object> rf = GrokService.responseFormatFor(GrokService.DRAW_GUIDE_SCHEMA_NAME);

      assertThat(rf).isNotNull();
      assertThat(rf.get("type")).isEqualTo("json_schema");

      Map<String, Object> js = (Map<String, Object>) rf.get("json_schema");
      assertThat(js.get("name")).isEqualTo("draw_guide_response");
      assertThat(js.get("strict")).isEqualTo(true);

      Map<String, Object> schema = (Map<String, Object>) js.get("schema");
      assertThat(schema.get("type")).isEqualTo("object");
      assertThat(schema.get("additionalProperties")).isEqualTo(false);
      // strict:true 규칙 — properties 의 모든 키가 required 에 있어야 함(스키마 거부 방지)
      assertThat((List<String>) schema.get("required"))
          .containsExactlyInAnyOrder("message", "citations", "offer_generate");

      Map<String, Object> props = (Map<String, Object>) schema.get("properties");
      assertThat(props).containsKeys("message", "citations", "offer_generate");

      Map<String, Object> citations = (Map<String, Object>) props.get("citations");
      assertThat(citations.get("type")).isEqualTo("array");
      assertThat(((Map<String, Object>) citations.get("items")).get("type")).isEqualTo("integer");
    }
  }
}
