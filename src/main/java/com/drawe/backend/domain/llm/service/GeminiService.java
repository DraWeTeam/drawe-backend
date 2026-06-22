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
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

/**
 * Gemini(Google) LLM 클라이언트. connect 3s / read 30s 타임아웃으로 hang 차단. 서킷 제외(실패는 {@code
 * AI_SERVICE_ERROR} 로 변환되어 상위에서 처리). 설계: {@code docs/decisions/S1-resilience4j-design.md}.
 */
@Slf4j
@Service
public class GeminiService implements LlmService {

  private final LlmProperties properties;
  private final RestClient restClient = HttpClientFactory.restClient(3000, 30000);

  public GeminiService(LlmProperties properties) {
    this.properties = properties;
  }

  @Override
  public LlmProvider provider() {
    return LlmProvider.GEMINI;
  }

  @Override
  public LlmCallResult generate(LlmCallContext context) {
    LlmProperties.Provider cfg = properties.getGemini();
    if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    Map<String, Object> body = buildBody(context);
    String url =
        cfg.getBaseUrl() + "/models/" + cfg.getModel() + ":generateContent?key=" + cfg.getApiKey();

    long start = System.currentTimeMillis();
    Map<?, ?> response =
        restClient
            .post()
            .uri(url)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);
    int latency = (int) (System.currentTimeMillis() - start);

    String content = extractText(response);
    return new LlmCallResult(content, cfg.getModel(), latency);
  }

  private Map<String, Object> buildBody(LlmCallContext context) {
    List<Map<String, Object>> contents = new ArrayList<>();

    String systemPrompt = null;
    for (LlmCallContext.Turn t : context.history()) {
      if (t.role() == MessageRole.SYSTEM) {
        systemPrompt = t.content();
        continue;
      }
      contents.add(textTurn(t.role() == MessageRole.USER ? "user" : "model", t.content()));
    }

    // 새 user 메시지 (텍스트 + 옵션 이미지)
    List<Map<String, Object>> parts = new ArrayList<>();
    parts.add(Map.of("text", context.newPrompt()));
    if (context.imageBytes() != null && context.imageBytes().length > 0) {
      parts.add(
          Map.of(
              "inline_data",
              Map.of(
                  "mime_type",
                  context.imageMimeType() != null ? context.imageMimeType() : "image/jpeg",
                  "data",
                  Base64.getEncoder().encodeToString(context.imageBytes()))));
    }
    contents.add(Map.of("role", "user", "parts", parts));

    Map<String, Object> body = new HashMap<>();
    body.put("contents", contents);
    if (systemPrompt != null) {
      body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemPrompt))));
    }
    return body;
  }

  private Map<String, Object> textTurn(String role, String text) {
    return Map.of("role", role, "parts", List.of(Map.of("text", text)));
  }

  @SuppressWarnings("unchecked")
  private String extractText(Map<?, ?> response) {
    try {
      List<Map<String, Object>> candidates = (List<Map<String, Object>>) response.get("candidates");
      Map<String, Object> content = (Map<String, Object>) candidates.get(0).get("content");
      List<Map<String, Object>> parts = (List<Map<String, Object>>) content.get("parts");
      return (String) parts.get(0).get("text");
    } catch (Exception e) {
      log.error("Failed to parse Gemini response: error_class={}", e.getClass().getSimpleName());
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }
}
