package com.drawe.backend.domain.llm.controller;

import com.drawe.backend.domain.llm.dto.ChatHistoryResponse;
import com.drawe.backend.domain.llm.dto.ChatRequest;
import com.drawe.backend.domain.llm.dto.ChatResponse;
import com.drawe.backend.domain.llm.service.ChatLlmService;
import com.drawe.backend.global.response.ApiResponse;
import com.drawe.backend.global.security.PrincipalDetails;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/projects/{projectId}/chat")
@RequiredArgsConstructor
public class ProjectChatController {

  private final ChatLlmService chatLlmService;

  @PostMapping
  public ApiResponse<ChatResponse> chat(
      @AuthenticationPrincipal PrincipalDetails principal,
      @PathVariable Long projectId,
      @Valid @RequestBody ChatRequest request) {
    return ApiResponse.success(chatLlmService.chat(principal.getUser(), projectId, request));
  }

  @GetMapping("/{sessionId}/history")
  public ApiResponse<ChatHistoryResponse> history(
      @AuthenticationPrincipal PrincipalDetails principal,
      @PathVariable Long projectId,
      @PathVariable String sessionId) {
    return ApiResponse.success(
        chatLlmService.getHistory(principal.getUser(), projectId, sessionId));
  }

  @PostMapping("/{sessionId}/reset")
  public ApiResponse<Map<String, Boolean>> reset(
      @AuthenticationPrincipal PrincipalDetails principal,
      @PathVariable Long projectId,
      @PathVariable String sessionId) {
    chatLlmService.resetSession(principal.getUser(), projectId, sessionId);
    return ApiResponse.success(Map.of("success", true));
  }
}
