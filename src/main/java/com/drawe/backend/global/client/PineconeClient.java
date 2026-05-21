package com.drawe.backend.global.client;

import com.drawe.backend.global.client.dto.PineconeMatch;
import com.drawe.backend.global.client.dto.PineconeQueryRequest;
import com.drawe.backend.global.client.dto.PineconeQueryResponse;
import com.drawe.backend.global.client.dto.PineconeUpsertRequest;
import com.drawe.backend.global.client.dto.PineconeVector;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
public class PineconeClient {
  private final WebClient webClient;

  public PineconeClient(
      @Value("${pinecone.host}") String pineconeHost, @Value("${pinecone.api-key}") String apiKey) {
    this.webClient =
        WebClient.builder()
            .baseUrl(pineconeHost)
            .defaultHeader("Api-Key", apiKey)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader("X-Pinecone-API-Version", "2024-07")
            .build();
  }

  /**
   * 주어진 벡터와 가장 유사한 top-K 이미지의 ID와 점수 반환.
   *
   * @param vector CLIP에서 생성된 768차원 정규화 벡터
   * @param topK 반환할 결과 개수
   * @return 유사도 순으로 정렬된 매치 리스트
   */
  public List<PineconeMatch> queryByVector(List<Float> vector, int topK) {
    try {
      PineconeQueryResponse response =
          webClient
              .post()
              .uri("/query")
              .bodyValue(PineconeQueryRequest.of(vector, topK))
              .retrieve()
              .bodyToMono(PineconeQueryResponse.class)
              .block();

      if (response == null || response.matches() == null) {
        log.warn("Pinecone 응답이 비어있습니다.");
        return List.of();
      }

      log.debug("Pinecone 검색 완료: 결과 {}개", response.matches().size());
      return response.matches();

    } catch (Exception e) {
      log.error("Pinecone 호출 실패: error={}", e.getMessage());
      throw new RuntimeException("벡터 검색 실패: " + e.getMessage(), e);
    }
  }

  /**
   * 벡터 하나를 Pinecone에 upsert. AI 이미지 적재용.
   *
   * @param id Pinecone vector ID. Image.sourceId와 동일 값을 사용 (예: "ai_1234")
   * @param vector L2 정규화된 768차원 CLIP 벡터
   * @param metadata 필터·노출용 메타. 최소 source, createdByUserId, prompt 포함 권장
   */
  public void upsert(String id, List<Float> vector, java.util.Map<String, Object> metadata) {
    PineconeUpsertRequest body =
        new PineconeUpsertRequest(List.of(new PineconeVector(id, vector, metadata)));
    webClient
        .post()
        .uri("/vectors/upsert")
        .bodyValue(body)
        .retrieve()
        .toBodilessEntity()
        .block();
    log.debug("Pinecone upsert 완료: id={}", id);
  }
}
