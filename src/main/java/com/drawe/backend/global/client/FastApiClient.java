package com.drawe.backend.global.client;

import com.drawe.backend.global.client.dto.EmbedRequest;
import com.drawe.backend.global.client.dto.EmbedResponse;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class FastApiClient {

  private final WebClient webClient;

  public FastApiClient(@Value("${fastapi.url}") String fastApiUrl) {
    // Content-Type 기본값은 JSON 으로 두고, multipart 요청은 BodyInserters로 호출 시점에 덮어쓴다.
    this.webClient =
        WebClient.builder()
            .baseUrl(fastApiUrl)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();
  }

  /** 텍스트 -> 768차원 CLIP 벡터로 변환. */
  public List<Float> embedText(String text) {
    try {
      EmbedResponse response =
          webClient
              .post()
              .uri("/embed/text")
              .bodyValue(new EmbedRequest(text))
              .retrieve()
              .bodyToMono(EmbedResponse.class)
              .block();

      if (response == null || response.embedding() == null) {
        throw new IllegalStateException("FastAPI 응답이 비었습니다.");
      }

      log.debug("FastAPI 임베딩 성공: text='{}', dimension={}", text, response.dimension());
      return response.embedding();
    } catch (Exception e) {
      log.error("FastAPI 호출 실패: text='{}', error={}", text, e.getMessage());
      throw new RuntimeException("임베딩 변환 실패: " + e.getMessage(), e);
    }
  }

  /**
   * 이미지 바이트를 CLIP으로 임베딩해 768차원 벡터를 반환.
   *
   * <p>텍스트 임베딩과 동일한 CLIP 모델(openai/clip-vit-large-patch14)·동일 정규화를 쓰므로 텍스트 쿼리 벡터와 같은 공간에서 비교 가능.
   * FastAPI 측 계약: POST /embed/image, multipart/form-data, field name = "image".
   */
  public List<Float> embedImage(byte[] imageBytes, String mimeType) {
    try {
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
              .block();

      if (response == null || response.embedding() == null) {
        throw new IllegalStateException("FastAPI 이미지 임베딩 응답이 비었습니다.");
      }

      log.debug("FastAPI 이미지 임베딩 성공: bytes={}, dimension={}", imageBytes.length, response.dimension());
      return response.embedding();
    } catch (Exception e) {
      log.error("FastAPI 이미지 임베딩 호출 실패: bytes={}, error={}", imageBytes.length, e.getMessage());
      throw new RuntimeException("이미지 임베딩 변환 실패: " + e.getMessage(), e);
    }
  }
}
