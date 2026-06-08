package com.drawe.backend.global.client;

import com.drawe.backend.global.client.dto.BriaGenerateResponse;
import com.drawe.backend.global.config.BriaProperties;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Bria 이미지 생성 클라이언트.
 *
 * <p>장애 격리: connect/read 타임아웃만 적용한다({@link HttpClientFactory}). 서킷브레이커는 의도적으로 제외 — Bria 는 자체
 * 폴링(최대 30s)과 에러 처리(HTTP 에러·폴링 실패 → {@code AI_SERVICE_ERROR})가 이미 견고하고, '생성 실패'라는 정상적 비즈니스
 * 결과까지 서킷을 열면 과민하기 때문. 설계: {@code docs/decisions/S1-resilience4j-design.md} §4.
 */
@Slf4j
@Component
public class BriaClient {

  private static final String GENERATE_PATH = "/v2/image/generate";
  private static final int POLL_MAX_ATTEMPTS = 30;
  private static final long POLL_INTERVAL_MS = 1000L;

  private final BriaProperties properties;
  // 개별 요청 기준 read 타임아웃 10s (폴링은 GET 한 건씩이라 충분). connect 3s.
  private final RestClient restClient = HttpClientFactory.restClient(3000, 10000);
  private final ObjectMapper objectMapper = new ObjectMapper();

  public BriaClient(BriaProperties properties) {
    this.properties = properties;
  }

  public BriaGenerateResponse generate(String prompt) {
    if (properties.getApiKey() == null || properties.getApiKey().isBlank()) {
      log.error("Bria API key missing");
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
    String url = properties.getBaseUrl() + GENERATE_PATH;
    Map<String, Object> body = Map.of("prompt", prompt);

    try {
      String raw =
          restClient
              .post()
              .uri(url)
              .header("api_token", properties.getApiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(String.class);

      log.info("Bria 응답 수신: response_length={}", raw == null ? 0 : raw.length());
      JsonNode root = objectMapper.readTree(raw);

      String imageUrl = extractImageUrl(root);
      if (imageUrl != null) {
        return new BriaGenerateResponse(imageUrl);
      }

      String statusUrl = root.path("status_url").asText(null);
      if (statusUrl != null && !statusUrl.isBlank()) {
        log.info("Bria async response detected, polling status_url={}", statusUrl);
        return pollForResult(statusUrl);
      }

      log.error(
          "Bria response missing image_url and status_url: response_length={}",
          raw == null ? 0 : raw.length());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    } catch (RestClientResponseException e) {
      log.error(
          "Bria HTTP error: status={}, body_length={}",
          e.getStatusCode(),
          e.getResponseBodyAsString() == null ? 0 : e.getResponseBodyAsString().length());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    } catch (CustomException e) {
      throw e;
    } catch (Exception e) {
      log.error("Bria call failed: url={}", url, e);
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }

  private BriaGenerateResponse pollForResult(String statusUrl) throws Exception {
    for (int attempt = 1; attempt <= POLL_MAX_ATTEMPTS; attempt++) {
      Thread.sleep(POLL_INTERVAL_MS);
      String raw =
          restClient
              .get()
              .uri(statusUrl)
              .header("api_token", properties.getApiKey())
              .retrieve()
              .body(String.class);

      if (attempt == 1 || attempt % 5 == 0) {
        log.info(
            "Bria poll attempt={} response_length={}", attempt, raw == null ? 0 : raw.length());
      }
      JsonNode node = objectMapper.readTree(raw);
      String imageUrl = extractImageUrl(node);
      if (imageUrl != null) {
        log.info("Bria poll succeeded after {} attempt(s)", attempt);
        return new BriaGenerateResponse(imageUrl);
      }
      String status = node.path("status").asText("");
      if ("failed".equalsIgnoreCase(status) || "error".equalsIgnoreCase(status)) {
        log.error("Bria generation failed: raw={}", raw);
        throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
      }
    }
    log.error("Bria poll timeout after {} attempts (statusUrl={})", POLL_MAX_ATTEMPTS, statusUrl);
    throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
  }

  private String extractImageUrl(JsonNode root) {
    if (root == null) return null;
    String direct = root.path("image_url").asText(null);
    if (direct != null && !direct.isBlank()) return direct;
    JsonNode result = root.path("result");
    if (result.isObject()) {
      String fromObj = result.path("image_url").asText(null);
      if (fromObj != null && !fromObj.isBlank()) return fromObj;
    }
    if (result.isArray() && result.size() > 0) {
      JsonNode first = result.get(0);
      String fromArray = first.path("image_url").asText(null);
      if (fromArray != null && !fromArray.isBlank()) return fromArray;
      JsonNode urls = first.path("urls");
      if (urls.isArray() && urls.size() > 0) {
        String u = urls.get(0).asText(null);
        if (u != null && !u.isBlank()) return u;
      }
    }
    return null;
  }
}
