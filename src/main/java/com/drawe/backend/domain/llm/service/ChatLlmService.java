package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.enums.LlmCallStatus;
import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.ChatHistoryResponse;
import com.drawe.backend.domain.llm.dto.ChatRequest;
import com.drawe.backend.domain.llm.dto.ChatResponse;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.domain.llm.repository.ChatSessionRepository;
import com.drawe.backend.domain.llm.repository.LlmMessageRepository;
import com.drawe.backend.domain.llm.repository.ProjectRepository;
import com.drawe.backend.global.config.LlmProperties;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatLlmService {

  private final ChatSessionRepository chatSessionRepository;
  private final ProjectRepository projectRepository;
  private final LlmMessageRepository llmMessageRepository;
  private final PersonaRegistry personaRegistry;
  private final LlmProperties llmProperties;
  private final ImageInputResolver imageInputResolver;
  private final List<LlmService> llmServices;

  @Transactional
  public ChatResponse chat(User user, Long projectId, ChatRequest request) {
    Project project = loadProjectAuthorized(user, projectId);
    ChatSession session = resolveOrCreateSession(user, project, request.sessionId());

    ImageInputResolver.Resolved image = imageInputResolver.resolve(request.imageUrl());

    List<LlmMessage> all = llmMessageRepository.findByChatSessionOrderByCreatedAtAsc(session);
    List<LlmCallContext.Turn> history = trimHistory(all, llmProperties.getMaxHistory());

    LlmProvider provider = resolveProvider();
    LlmService llm = pickService(provider);
    LlmCallContext ctx =
        new LlmCallContext(history, request.message(), image.bytes(), image.mimeType());

    // 사용자 메시지 저장 (LLM 호출 전: 실패해도 발화는 남김)
    LlmMessage userMsg = new LlmMessage();
    userMsg.setChatSession(session);
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent(request.message());
    userMsg.setHasImage(image.hasImage());
    userMsg.setImageUrl(image.hasImage() ? "(base64-data)" : null);
    llmMessageRepository.save(userMsg);

    LlmMessage assistantMsg = new LlmMessage();
    assistantMsg.setChatSession(session);
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setProvider(provider);
    assistantMsg.setHasImage(false);

    try {
      LlmCallResult result = llm.generate(ctx);
      assistantMsg.setContent(result.content());
      assistantMsg.setModel(result.model());
      assistantMsg.setLatencyMs(result.latencyMs());
      assistantMsg.setStatus(LlmCallStatus.SUCCESS);
      llmMessageRepository.save(assistantMsg);
      session.setLastActive(Instant.now());

      return new ChatResponse(
          session.getId(), "guide", result.content(), Collections.emptyList(), null);
    } catch (CustomException e) {
      persistFailure(assistantMsg, e);
      throw e;
    } catch (Exception e) {
      log.error("LLM call failed for session={} provider={}", session.getId(), provider, e);
      persistFailure(assistantMsg, e);
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }

  @Transactional(readOnly = true)
  public ChatHistoryResponse getHistory(User user, Long projectId, String sessionId) {
    loadProjectAuthorized(user, projectId);
    ChatSession session = loadSessionAuthorized(user, sessionId, projectId);
    List<ChatHistoryResponse.HistoryItem> items =
        llmMessageRepository.findByChatSessionOrderByCreatedAtAsc(session).stream()
            .filter(m -> m.getRole() != MessageRole.SYSTEM)
            .map(ChatHistoryResponse.HistoryItem::from)
            .toList();
    return new ChatHistoryResponse(session.getId(), items);
  }

  @Transactional
  public void resetSession(User user, Long projectId, String sessionId) {
    loadProjectAuthorized(user, projectId);
    ChatSession session = loadSessionAuthorized(user, sessionId, projectId);
    List<LlmMessage> messages = llmMessageRepository.findByChatSessionOrderByCreatedAtAsc(session);
    List<LlmMessage> nonSystem =
        messages.stream().filter(m -> m.getRole() != MessageRole.SYSTEM).toList();
    llmMessageRepository.deleteAll(nonSystem);
    session.setLastActive(Instant.now());
  }

  private Project loadProjectAuthorized(User user, Long projectId) {
    Project project =
        projectRepository
            .findById(projectId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    if (!project.getUser().getId().equals(user.getId())) {
      throw new CustomException(ErrorCode.FORBIDDEN);
    }
    return project;
  }

  private ChatSession loadSessionAuthorized(User user, String sessionId, Long projectId) {
    ChatSession session =
        chatSessionRepository
            .findById(sessionId)
            .orElseThrow(() -> new CustomException(ErrorCode.NOT_FOUND));
    if (!session.getUser().getId().equals(user.getId())
        || !session.getProject().getId().equals(projectId)) {
      throw new CustomException(ErrorCode.FORBIDDEN);
    }
    return session;
  }

  private ChatSession resolveOrCreateSession(User user, Project project, String sessionId) {
    if (sessionId == null || sessionId.isBlank()) {
      return createSessionWithPersona(user, project);
    }
    return loadSessionAuthorized(user, sessionId, project.getId());
  }

  private ChatSession createSessionWithPersona(User user, Project project) {
    ChatSession session = new ChatSession();
    session.setId(UUID.randomUUID().toString());
    session.setUser(user);
    session.setProject(project);
    session.setLastActive(Instant.now());
    chatSessionRepository.save(session);

    LlmMessage system = new LlmMessage();
    system.setChatSession(session);
    system.setRole(MessageRole.SYSTEM);
    system.setContent(personaRegistry.resolve(PersonaRegistry.DEFAULT_KEY));
    system.setHasImage(false);
    llmMessageRepository.save(system);
    return session;
  }

  private LlmProvider resolveProvider() {
    String code = llmProperties.getDefaultProvider();
    if (code == null || code.isBlank()) {
      return LlmProvider.GEMINI;
    }
    return LlmProvider.fromCode(code);
  }

  private LlmService pickService(LlmProvider provider) {
    return llmServices.stream()
        .filter(s -> s.provider() == provider)
        .findFirst()
        .orElseThrow(() -> new CustomException(ErrorCode.INVALID_INPUT));
  }

  private List<LlmCallContext.Turn> trimHistory(List<LlmMessage> all, int maxNonSystem) {
    List<LlmCallContext.Turn> systems = new ArrayList<>();
    List<LlmCallContext.Turn> rest = new ArrayList<>();
    for (LlmMessage m : all) {
      if (m.getStatus() == LlmCallStatus.FAILED) {
        continue;
      }
      LlmCallContext.Turn turn = new LlmCallContext.Turn(m.getRole(), m.getContent());
      if (m.getRole() == MessageRole.SYSTEM) {
        systems.add(turn);
      } else {
        rest.add(turn);
      }
    }
    int from = Math.max(0, rest.size() - maxNonSystem);
    List<LlmCallContext.Turn> trimmed = new ArrayList<>(systems);
    trimmed.addAll(rest.subList(from, rest.size()));
    return trimmed;
  }

  private void persistFailure(LlmMessage assistantMsg, Exception e) {
    assistantMsg.setContent("");
    assistantMsg.setStatus(LlmCallStatus.FAILED);
    assistantMsg.setErrorMessage(safeError(e));
    llmMessageRepository.save(assistantMsg);
  }

  private String safeError(Exception e) {
    String msg = e.getMessage();
    if (msg == null) {
      return e.getClass().getSimpleName();
    }
    return msg.length() > 1000 ? msg.substring(0, 1000) : msg;
  }
}
