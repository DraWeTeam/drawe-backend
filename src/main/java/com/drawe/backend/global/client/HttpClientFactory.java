package com.drawe.backend.global.client;

import java.time.Duration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * connect/read 타임아웃이 박힌 {@link RestClient} 를 만드는 공통 팩토리.
 *
 * <p>기본 {@code RestClient.create()} 는 타임아웃이 없어 외부 API 가 hang 하면 톰캣 스레드가 무한 대기 → 풀 고갈로
 * 서비스 전체가 마비된다. 모든 동기 RestClient(Bria, LLM 3종)는 이 팩토리로 생성해 타임아웃을 강제한다. 설계: {@code
 * docs/decisions/S1-resilience4j-design.md}.
 */
public final class HttpClientFactory {

  private HttpClientFactory() {}

  /**
   * @param connectTimeoutMs 커넥션 수립 타임아웃 (ms)
   * @param readTimeoutMs 응답 대기 타임아웃 (ms) — 개별 요청 기준
   */
  public static RestClient restClient(int connectTimeoutMs, int readTimeoutMs) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofMillis(connectTimeoutMs));
    factory.setReadTimeout(Duration.ofMillis(readTimeoutMs));
    return RestClient.builder().requestFactory(factory).build();
  }
}
