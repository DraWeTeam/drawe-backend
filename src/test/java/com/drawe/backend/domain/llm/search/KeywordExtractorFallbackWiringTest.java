package com.drawe.backend.domain.llm.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.drawe.backend.domain.llm.service.GrokService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link KeywordExtractorFallback} 빈 배선 검증.
 *
 * <p>단위 테스트({@code GrokKeywordExtractorFallbackTest})는 GrokService mock 으로 파싱만 본다.
 * 본 테스트는 그와 별개로 <b>컨텍스트 로딩 시 활성 fallback 빈이 무엇인지</b>를 검증한다 —
 * 즉 {@link GrokKeywordExtractorFallback} 가 등록되면 {@link NoopKeywordExtractorFallback} 의
 * {@code @ConditionalOnMissingBean} 이 깨져 Noop 이 비활성화되는지(= 실제 폴백이 Grok 인지).
 *
 * <p>DB/Redis/외부 의존성 없이 {@link ApplicationContextRunner} 로 fallback 빈들만 띄운다.
 */
class KeywordExtractorFallbackWiringTest {

  /** Grok 구현체와 그 의존성(GrokService mock, ObjectMapper)을 제공. */
  @Configuration
  static class GrokConfig {
    @Bean
    GrokService grokService() {
      return mock(GrokService.class);
    }

    @Bean
    ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    @Bean
    GrokKeywordExtractorFallback grokKeywordExtractorFallback(
        GrokService grokService, ObjectMapper objectMapper) {
      return new GrokKeywordExtractorFallback(grokService, objectMapper);
    }
  }

  private final ApplicationContextRunner runner = new ApplicationContextRunner();

  @Test
  @DisplayName("Grok 구현 등록 시 — 활성 fallback 은 Grok 단 하나, Noop 비활성")
  void grokReplacesNoop() {
    runner
        .withUserConfiguration(GrokConfig.class, NoopKeywordExtractorFallback.class)
        .run(
            ctx -> {
              // 두 구현이 다 살아있으면 hasSingleBean 실패 → 주입 모호성(NoUniqueBean) 사전 차단
              assertThat(ctx).hasSingleBean(KeywordExtractorFallback.class);
              assertThat(ctx.getBean(KeywordExtractorFallback.class))
                  .isInstanceOf(GrokKeywordExtractorFallback.class);
            });
  }

  @Test
  @DisplayName("Grok 구현 없을 때 — Noop 이 fallback (빈값 반환, 회귀 가드)")
  void noopWhenNoGrokImpl() {
    runner
        .withUserConfiguration(NoopKeywordExtractorFallback.class)
        .run(
            ctx -> {
              assertThat(ctx).hasSingleBean(KeywordExtractorFallback.class);
              // Noop 은 람다라 타입 비교 대신 동작으로 확인: 항상 빈 리스트
              assertThat(ctx.getBean(KeywordExtractorFallback.class).extract("아무 입력"))
                  .isEmpty();
            });
  }
}
