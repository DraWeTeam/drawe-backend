package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.global.config.LlmProperties;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class ClaudeService implements LlmService {

  private static final String ANTHROPIC_VERSION = "2023-06-01";
  private static final int DEFAULT_MAX_TOKENS = 1024;

  private final LlmProperties properties;
  private final RestClient restClient = RestClient.create();

  @Override
  public LlmProvider provider() {
    return LlmProvider.CLAUDE;
  }

  @Override
  public LlmCallResult generate(LlmCallContext context) {
    LlmProperties.Provider cfg = properties.getClaude();
    if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    Map<String, Object> body = buildBody(cfg.getModel(), context);
    String url = cfg.getBaseUrl() + "/messages";

    long start = System.currentTimeMillis();
    Map<?, ?> response =
        restClient
            .post()
            .uri(url)
            .header("x-api-key", cfg.getApiKey())
            .header("anthropic-version", ANTHROPIC_VERSION)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);
    int latency = (int) (System.currentTimeMillis() - start);

    logCacheUsage(response);
    String content = extractText(response);
    return new LlmCallResult(content, cfg.getModel(), latency);
  }

  private Map<String, Object> buildBody(String model, LlmCallContext context) {
    Map<String, Object> body = new HashMap<>();
    body.put("model", model);
    body.put("max_tokens", DEFAULT_MAX_TOKENS);

    String systemPrompt = null;
    List<Map<String, Object>> messages = new ArrayList<>();

    for (LlmCallContext.Turn t : context.history()) {
      if (t.role() == MessageRole.SYSTEM) {
        systemPrompt = t.content();
        continue;
      }
      messages.add(
          Map.of(
              "role", t.role() == MessageRole.USER ? "user" : "assistant", "content", t.content()));
    }

    // 새 user 메시지 (이미지 있으면 멀티모달 블록)
    if (context.imageBytes() != null && context.imageBytes().length > 0) {
      String mime = context.imageMimeType() != null ? context.imageMimeType() : "image/jpeg";
      List<Map<String, Object>> parts =
          List.of(
              Map.of(
                  "type",
                  "image",
                  "source",
                  Map.of(
                      "type",
                      "base64",
                      "media_type",
                      mime,
                      "data",
                      Base64.getEncoder().encodeToString(context.imageBytes()))),
              Map.of("type", "text", "text", context.newPrompt()));
      messages.add(Map.of("role", "user", "content", parts));
    } else {
      messages.add(Map.of("role", "user", "content", context.newPrompt()));
    }

    // system 프롬프트는 cache_control을 위해 블록 배열로 전달
    if (systemPrompt != null) {
      body.put(
          "system",
          List.of(
              Map.of(
                  "type",
                  "text",
                  "text",
                  systemPrompt,
                  "cache_control",
                  Map.of("type", "ephemeral"))));
    }
    body.put("messages", messages);
    return body;
  }

  @SuppressWarnings("unchecked")
  private String extractText(Map<?, ?> response) {
    try {
      List<Map<String, Object>> contentBlocks = (List<Map<String, Object>>) response.get("content");
      StringBuilder sb = new StringBuilder();
      for (Map<String, Object> block : contentBlocks) {
        if ("text".equals(block.get("type"))) {
          sb.append((String) block.get("text"));
        }
      }
      return sb.toString();
    } catch (Exception e) {
      log.error("Failed to parse Claude response: {}", response, e);
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }

  private void logCacheUsage(Map<?, ?> response) {
    Object usage = response.get("usage");
    if (usage instanceof Map<?, ?> u) {
      Object created = u.get("cache_creation_input_tokens");
      Object read = u.get("cache_read_input_tokens");
      Object input = u.get("input_tokens");
      log.debug("Claude usage - input={}, cache_created={}, cache_read={}", input, created, read);
    }
  }
}
