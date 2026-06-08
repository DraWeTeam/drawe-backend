package com.drawe.backend.global.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.RetryRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import io.github.resilience4j.springboot3.circuitbreaker.autoconfigure.CircuitBreakerAutoConfiguration;
import io.github.resilience4j.springboot3.retry.autoconfigure.RetryAutoConfiguration;

/**
 * Resilience4j 설정이 application.properties 에서 의도대로 로드되는지 검증하는 가벼운 슬라이스 테스트.
 *
 * <p>전체 {@code @SpringBootTest}(DB/Redis/OAuth 필요) 대신, Resilience4j auto-config 만 올려 서킷·재시도
 * 인스턴스(embed/vector)와 그 임계치가 properties 대로 등록됐는지 확인한다. 설계: {@code
 * docs/decisions/S1-resilience4j-design.md}.
 */
@SpringBootTest(classes = ResilienceConfigTest.TestConfig.class)
class ResilienceConfigTest {

  @Configuration
  @ImportAutoConfiguration({CircuitBreakerAutoConfiguration.class, RetryAutoConfiguration.class})
  static class TestConfig {}

  @Autowired CircuitBreakerRegistry circuitBreakerRegistry;
  @Autowired RetryRegistry retryRegistry;

  @Test
  @DisplayName("embed/vector 서킷 인스턴스가 default 설정대로 로드된다")
  void circuitBreakerInstancesLoaded() {
    var embed = circuitBreakerRegistry.circuitBreaker("embed");
    var vector = circuitBreakerRegistry.circuitBreaker("vector");

    assertThat(embed.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(50f);
    assertThat(embed.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(5);
    assertThat(vector.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(10);
  }

  @Test
  @DisplayName("LLM·Bria 서킷 인스턴스는 정의하지 않았다 (설계 §4 — 타임아웃만)")
  void llmImagegenNotConfiguredExplicitly() {
    // 명시 인스턴스가 아니므로 default config 가 아니라 fallback default 로 생성된다.
    // 핵심: properties 에 별도 정의를 두지 않았음을 회귀 방지로 박는다.
    assertThat(circuitBreakerRegistry.find("llm")).isEmpty();
    assertThat(circuitBreakerRegistry.find("imagegen")).isEmpty();
  }

  @Test
  @DisplayName("embed/vector 재시도가 maxAttempts=2 로 로드된다")
  void retryInstancesLoaded() {
    assertThat(retryRegistry.retry("embed").getRetryConfig().getMaxAttempts()).isEqualTo(2);
    assertThat(retryRegistry.retry("vector").getRetryConfig().getMaxAttempts()).isEqualTo(2);
  }
}
