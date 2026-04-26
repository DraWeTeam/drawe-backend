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
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Slf4j
@Service
@RequiredArgsConstructor
public class GrokService implements LlmService {

  private final LlmProperties properties;
  private final RestClient restClient = RestClient.create();

  @Override
  public LlmProvider provider() {
    return LlmProvider.GROK;
  }

  @Override
  public LlmCallResult generate(LlmCallContext context) {
    LlmProperties.Provider cfg = properties.getGrok();
    if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }

    Map<String, Object> body = buildBody(cfg.getModel(), context);
    String url = cfg.getBaseUrl() + "/chat/completions";

    long start = System.currentTimeMillis();
    Map<?, ?> response =
        restClient
            .post()
            .uri(url)
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + cfg.getApiKey())
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(Map.class);
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
    return body;
  }

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
      log.error("Failed to parse Grok response: {}", response, e);
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }
}
