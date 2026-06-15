package com.drawe.backend.domain.llm.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.domain.llm.service.GrokService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link GrokKeywordExtractorFallback} 파싱·장애격리 테스트.
 *
 * <p>GrokService 는 mock — 실제 LLM/HTTP 없이 응답 문자열만 제어해 파싱 로직과 예외 처리를 검증한다.
 */
class GrokKeywordExtractorFallbackTest {

  private GrokService grokService;
  private GrokKeywordExtractorFallback fallback;

  @BeforeEach
  void setUp() {
    grokService = mock(GrokService.class);
    fallback = new GrokKeywordExtractorFallback(grokService, new ObjectMapper());
  }

  private LlmCallResult result(String content) {
    return new LlmCallResult(content, "grok-test", 100);
  }

  @Test
  @DisplayName("정상 JSON 배열 → 소문자 키워드 리스트")
  void parsesJsonArray() {
    when(grokService.generate(any())).thenReturn(result("[\"Dynamic\",\"Cat\",\"Pose\"]"));

    assertThat(fallback.extract("역동적인 고양이 포즈"))
        .containsExactly("dynamic", "cat", "pose");
  }

  @Test
  @DisplayName("코드펜스·설명 섞인 응답에서도 배열만 추출")
  void parsesArrayFromNoisyResponse() {
    when(grokService.generate(any()))
        .thenReturn(result("키워드: ```json\n[\"watercolor\", \"landscape\"]\n``` 입니다"));

    assertThat(fallback.extract("수채화 풍경"))
        .containsExactly("watercolor", "landscape");
  }

  @Test
  @DisplayName("중복 제거 + 빈 문자열 필터 + 6개 상한")
  void dedupFilterAndLimit() {
    when(grokService.generate(any()))
        .thenReturn(result("[\"cat\",\"cat\",\"\",\"  \",\"pose\"]"));

    assertThat(fallback.extract("고양이 포즈")).containsExactly("cat", "pose");
  }

  @Test
  @DisplayName("GrokService 예외 → 빈 리스트 (요청 보호, null 금지)")
  void grokFailureReturnsEmpty() {
    when(grokService.generate(any())).thenThrow(new RuntimeException("grok down"));

    assertThat(fallback.extract("고양이")).isNotNull().isEmpty();
  }

  @Test
  @DisplayName("깨진 JSON 응답 → 빈 리스트")
  void malformedJsonReturnsEmpty() {
    when(grokService.generate(any())).thenReturn(result("죄송하지만 키워드가 없습니다"));

    assertThat(fallback.extract("고양이")).isEmpty();
  }

  @Test
  @DisplayName("빈/null 입력 → Grok 호출조차 안 함")
  void blankInputSkipsGrok() {
    assertThat(fallback.extract(null)).isEmpty();
    assertThat(fallback.extract("   ")).isEmpty();

    verify(grokService, never()).generate(any());
  }
}