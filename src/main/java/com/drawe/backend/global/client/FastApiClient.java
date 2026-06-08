package com.drawe.backend.global.client;

import com.drawe.backend.global.client.dto.EmbedRequest;
import com.drawe.backend.global.client.dto.EmbedResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * CLIP 임베딩(FastAPI) 클라이언트.
 *
 * <p>외부 장애 격리: 응답 타임아웃({@code .timeout})으로 무한 대기를 막고, {@code @CircuitBreaker(name="embed")} +
 * {@code @Retry(name="embed")} 로 연속 실패 시 호출을 차단·재시도한다. 설계: {@code
 * docs/decisions/S1-resilience4j-design.md}.
 *
 * <p><b>주의</b>: Resilience4j 어노테이션이 예외 타입으로 서킷·재시도를 판정하므로, 원래 예외({@code TimeoutException} 등)를
 * 다른 타입으로 감싸지 않고 그대로 전파한다. (이전 구현은 모든 예외를 {@code RuntimeException} 으로 감싸 서킷이 발화하지 못했다.)
 */
@Slf4j
@Component
public class FastApiClient {

  private final WebClient webClient;
  private final Duration timeout;

  public FastApiClient(
      @Value("${fastapi.url}") String fastApiUrl,
      @Value("${fastapi.timeout-ms:5000}") long timeoutMs) {
    // Content-Type 기본값은 JSON 으로 두고, multipart 요청은 BodyInserters로 호출 시점에 덮어쓴다.
    this.webClient =
        WebClient.builder()
            .baseUrl(fastApiUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
    this.timeout = Duration.ofMillis(timeoutMs);
  }

  /** 텍스트 -> 768차원 CLIP 벡터로 변환. */
  @CircuitBreaker(name = "embed")
  @Retry(name = "embed")
  public List<Float> embedText(String text) {
    EmbedResponse response =
        webClient
            .post()
            .uri("/embed/text")
            .bodyValue(new EmbedRequest(text))
            .retrieve()
            .bodyToMono(EmbedResponse.class)
            .timeout(timeout)
            .block();

    if (response == null || response.embedding() == null) {
      throw new IllegalStateException("FastAPI 응답이 비었습니다.");
    }

    log.debug("FastAPI 임베딩 성공: dimension={}", response.dimension());
    return response.embedding();
  }

  /**
   * 이미지 바이트를 CLIP으로 임베딩해 768차원 벡터를 반환.
   *
   * <p>텍스트 임베딩과 동일한 CLIP 모델(openai/clip-vit-large-patch14)·동일 정규화를 쓰므로 텍스트 쿼리 벡터와 같은 공간에서 비교 가능.
   * FastAPI 측 계약: POST /embed/image, multipart/form-data, field name = "image".
   */
  @CircuitBreaker(name = "embed")
  @Retry(name = "embed")
  public List<Float> embedImage(byte[] imageBytes, String mimeType) {
    MultipartBodyBuilder builder = new MultipartBodyBuilder();
    builder
        .part(
            "image",
            new ByteArrayResource(imageBytes) {
              @Override
              public String getFilename() {
                return "image";
              }
            })
        .contentType(MediaType.parseMediaType(mimeType));

    EmbedResponse response =
        webClient
            .post()
            .uri("/embed/image")
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(BodyInserters.fromMultipartData(builder.build()))
            .retrieve()
            .bodyToMono(EmbedResponse.class)
            .timeout(timeout)
            .block();

    if (response == null || response.embedding() == null) {
      throw new IllegalStateException("FastAPI 이미지 임베딩 응답이 비었습니다.");
    }

    log.debug("FastAPI 이미지 임베딩 성공: bytes={}, dimension={}", imageBytes.length, response.dimension());
    return response.embedding();
  }
}
