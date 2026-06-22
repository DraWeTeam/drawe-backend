package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.global.client.HttpClientFactory;
import com.drawe.backend.global.config.LlmProperties;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Grok(xAI) LLM 클라이언트. connect 3s / read 30s 타임아웃으로 hang 차단(LLM 응답은 본래 느려 read 는 길게). 서킷은 제외 — 실패는
 * 이미 {@code AI_SERVICE_ERROR} 로 변환되어 {@code ChatLlmService} 가 처리. 설계: {@code
 * docs/decisions/S1-resilience4j-design.md}.
 */
@Slf4j
@Service
public class GrokService implements LlmService {

  private final LlmProperties properties;
  private final RestClient restClient = HttpClientFactory.restClient(3000, 30000);

  public GrokService(LlmProperties properties) {
    this.properties = properties;
  }

  @Override
  public LlmProvider provider() {
    return LlmProvider.GROK;
  }

  @Override
  public LlmCallResult generate(LlmCallContext context) {
    LlmProperties.Provider cfg = properties.getGrok();
    if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
      log.error("Grok API key missing. model={}, baseUrl={}", cfg.getModel(), cfg.getBaseUrl());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    Map<String, Object> body = buildBody(cfg.getModel(), context);
    String url = cfg.getBaseUrl() + "/chat/completions";

    long start = System.currentTimeMillis();
    Map<?, ?> response;
    try {
      response =
          restClient
              .post()
              .uri(url)
              .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getApiKey())
              .contentType(MediaType.APPLICATION_JSON)
              .body(body)
              .retrieve()
              .body(Map.class);
    } catch (org.springframework.web.client.RestClientResponseException e) {
      log.error("Grok HTTP error: status={}", e.getStatusCode());
      log.debug("Grok HTTP error body: {}", e.getResponseBodyAsString());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    } catch (Exception e) {
      log.error(
          "Grok call failed: url={}, model={}, error_class={}",
          url,
          cfg.getModel(),
          e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
    int latency = (int) (System.currentTimeMillis() - start);

    String content = extractText(response);
    return new LlmCallResult(content, cfg.getModel(), latency);
  }

  private Map<String, Object> buildBody(String model, LlmCallContext context) {
    List<Map<String, Object>> messages = new ArrayList<>();

    for (LlmCallContext.Turn t : context.history()) {
      messages.add(Map.of("role", roleName(t.role()), "content", t.content()));
    }

    // 새 user 메시지 (이미지 있으면 멀티모달 컨텐츠)
    if (context.imageBytes() != null && context.imageBytes().length > 0) {
      String mime = context.imageMimeType() != null ? context.imageMimeType() : "image/jpeg";
      String dataUri =
          "data:" + mime + ";base64," + Base64.getEncoder().encodeToString(context.imageBytes());
      List<Map<String, Object>> parts =
          List.of(
              Map.of("type", "text", "text", context.newPrompt()),
              Map.of("type", "image_url", "image_url", Map.of("url", dataUri)));
      messages.add(Map.of("role", "user", "content", parts));
    } else {
      messages.add(Map.of("role", "user", "content", context.newPrompt()));
    }

    Map<String, Object> body = new HashMap<>();
    body.put("model", model);
    body.put("messages", messages);

    // Structured Output 강제 (S2' Phase 3). responseSchemaName 이 지정된 호출(COMPOSE)만 네이티브
    // json_schema 모드로. 미지정(TRANSLATE·KeywordExtractor 등)은 기존대로 평문. 알 수 없는 이름은
    // 평문 폴백 — strict 거부로 응답을 깨뜨리느니 안전하게(설계 §4.3).
    Map<String, Object> responseFormat = responseFormatFor(context.responseSchemaName());
    if (responseFormat != null) {
      body.put("response_format", responseFormat);
    }
    return body;
  }

  /**
   * 스키마 이름 → OpenAI 호환 {@code response_format} 매핑. 현재는 COMPOSE 가이드 응답 스키마 1종. null/blank/미등록 이름은
   * {@code null} 반환(평문). 순수 함수 — 테스트 용이성을 위해 분리.
   */
  static Map<String, Object> responseFormatFor(String schemaName) {
    if (schemaName == null || schemaName.isBlank()) {
      return null;
    }
    if (DRAW_GUIDE_SCHEMA_NAME.equals(schemaName)) {
      return Map.of(
          "type",
          "json_schema",
          "json_schema",
          Map.of("name", DRAW_GUIDE_SCHEMA_NAME, "strict", true, "schema", DRAW_GUIDE_SCHEMA));
    }
    log.warn("알 수 없는 responseSchemaName='{}' — 평문으로 폴백", schemaName);
    return null;
  }

  /** COMPOSE 가이드 응답 스키마 이름 — {@code LlmCallContext.responseSchemaName} 과 매칭. */
  public static final String DRAW_GUIDE_SCHEMA_NAME = "draw_guide_response";

  /**
   * COMPOSE 가이드 응답 스키마(설계 §4.1). message=본문, citations=인용한 references 1-based 인덱스,
   * offer_generate=자료 부족 시 생성 제안(LLM 의견; 최종 노출은 시스템이 결정).
   *
   * <p><b>strict 규칙</b>: xAI/OpenAI 호환 {@code strict:true} 는 properties 의 <i>모든</i> 키가 {@code
   * required} 에 있고 {@code additionalProperties:false} 일 것을 요구한다(스키마 거부 방지). 따라서 offer_generate 도
   * required 에 포함 — boolean 이라 LLM 이 항상 채워도 부담이 적고, 값 자체는 보조 신호일 뿐.
   */
  private static final Map<String, Object> DRAW_GUIDE_SCHEMA =
      Map.of(
          "type",
          "object",
          "additionalProperties",
          false,
          "required",
          List.of("message", "citations", "offer_generate"),
          "properties",
          Map.of(
              "message", Map.of("type", "string"),
              "citations", Map.of("type", "array", "items", Map.of("type", "integer")),
              "offer_generate", Map.of("type", "boolean")));

  private String roleName(MessageRole role) {
    return switch (role) {
      case SYSTEM -> "system";
      case USER -> "user";
      case ASSISTANT -> "assistant";
    };
  }

  @SuppressWarnings("unchecked")
  private String extractText(Map<?, ?> response) {
    try {
      List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
      Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
      return (String) message.get("content");
    } catch (Exception e) {
      log.error("Failed to parse Grok response: error_class={}", e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }
}
