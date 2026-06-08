package com.drawe.backend.global.client;

import com.drawe.backend.global.client.dto.PineconeMatch;
import com.drawe.backend.global.client.dto.PineconeQueryRequest;
import com.drawe.backend.global.client.dto.PineconeQueryResponse;
import com.drawe.backend.global.client.dto.PineconeUpsertRequest;
import com.drawe.backend.global.client.dto.PineconeVector;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Pinecone 벡터 검색/적재 클라이언트.
 *
 * <p>외부 장애 격리: 응답 타임아웃({@code .timeout}) + {@code @CircuitBreaker(name="vector")} +
 * {@code @Retry(name="vector")}. 설계: {@code docs/decisions/S1-resilience4j-design.md}.
 *
 * <p><b>주의</b>: 어노테이션이 예외 타입으로 서킷·재시도를 판정하므로 원래 예외를 감싸지 않고 그대로 전파한다. 검색 경로의 예외는 상위
 * {@code ChatLlmService.handleSearchDecision} 가 빈 레퍼런스(graceful)로, 적재 경로는 {@code AiImageIndexService}
 * 의 비동기 catch 가 받는다 — 별도 폴백 메서드 불필요.
 */
@Slf4j
@Component
public class PineconeClient {
  private final WebClient webClient;
  private final Duration timeout;

  public PineconeClient(
      @Value("${pinecone.host}") String pineconeHost,
      @Value("${pinecone.api-key}") String apiKey,
      @Value("${pinecone.timeout-ms:5000}") long timeoutMs) {
    this.webClient =
        WebClient.builder()
            .baseUrl(pineconeHost)
            .defaultHeader("Api-Key", apiKey)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("X-Pinecone-API-Version", "2024-07")
            .build();
    this.timeout = Duration.ofMillis(timeoutMs);
  }

  /**
   * 주어진 벡터와 가장 유사한 top-K 이미지의 ID와 점수 반환.
   *
   * @param vector CLIP에서 생성된 768차원 정규화 벡터
   * @param topK 반환할 결과 개수
   * @return 유사도 순으로 정렬된 매치 리스트
   */
  @CircuitBreaker(name = "vector")
  @Retry(name = "vector")
  public List<PineconeMatch> queryByVector(List<Float> vector, int topK) {
    PineconeQueryResponse response =
        webClient
            .post()
            .uri("/query")
            .bodyValue(PineconeQueryRequest.of(vector, topK))
            .retrieve()
            .bodyToMono(PineconeQueryResponse.class)
            .timeout(timeout)
            .block();

    if (response == null || response.matches() == null) {
      log.warn("Pinecone 응답이 비어있습니다.");
      return List.of();
    }

    log.debug("Pinecone 검색 완료: 결과 {}개", response.matches().size());
    return response.matches();
  }

  /**
   * 벡터 하나를 Pinecone에 upsert. AI 이미지 적재용.
   *
   * @param id Pinecone vector ID. Image.sourceId와 동일 값을 사용 (예: "ai_1234")
   * @param vector L2 정규화된 768차원 CLIP 벡터
   * @param metadata 필터·노출용 메타. 최소 source, createdByUserId, prompt 포함 권장
   */
  @CircuitBreaker(name = "vector")
  @Retry(name = "vector")
  public void upsert(String id, List<Float> vector, java.util.Map<String, Object> metadata) {
    PineconeUpsertRequest body =
        new PineconeUpsertRequest(List.of(new PineconeVector(id, vector, metadata)));
    webClient
        .post()
        .uri("/vectors/upsert")
        .bodyValue(body)
        .retrieve()
        .toBodilessEntity()
        .timeout(timeout)
        .block();
    log.debug("Pinecone upsert 완료: id={}", id);
  }
}
