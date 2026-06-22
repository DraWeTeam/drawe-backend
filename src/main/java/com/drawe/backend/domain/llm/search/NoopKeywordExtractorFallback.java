package com.drawe.backend.domain.llm.search;

import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * {@link KeywordExtractorFallback} 의 기본(no-op) 구현.
 *
 * <p>Grok 연결 전 임시. 호출 시 WARN 로그 + 빈 리스트 반환 → 검색이 안 되지만 시스템 크래시는 안 함.
 *
 * <p>실제 LLM 폴백 추가 시:
 *
 * <ol>
 *   <li>{@code GrokKeywordExtractorFallback implements KeywordExtractorFallback} 작성
 *   <li>{@code @Component} 또는 {@code @Bean} 으로 등록
 *   <li>이 클래스의 {@code @ConditionalOnMissingBean} 덕에 자동으로 비활성화
 * </ol>
 *
 * <p>즉, Grok 구현체가 등록되면 이 NoopFallback 은 Spring 컨텍스트에서 사라짐.
 */
@Slf4j
@Configuration
public class NoopKeywordExtractorFallback {

  @Bean
  @ConditionalOnMissingBean(KeywordExtractorFallback.class)
  public KeywordExtractorFallback noopFallback() {
    log.warn(
        "KeywordExtractorFallback 구현체가 없어 Noop 사용 — LLM 폴백 비활성. "
            + "실제 Grok 연결 시 GrokKeywordExtractorFallback 등록 필요.");
    return cleanedMessage -> {
      log.warn(
          "Fallback called but not configured. Returning empty for: '{}'",
          truncate(cleanedMessage, 50));
      return List.of();
    };
  }

  private static String truncate(String s, int maxLen) {
    if (s == null) {
      return "";
    }
    return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
  }
}
