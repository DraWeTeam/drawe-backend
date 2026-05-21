package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.analytics.AnalyticsEventType;
import com.drawe.backend.domain.analytics.service.AnalyticsEventService;
import com.drawe.backend.domain.enums.LlmCallStatus;
import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.enums.UserPlan;
import com.drawe.backend.domain.llm.dto.*;
import com.drawe.backend.domain.llm.repository.ChatSessionRepository;
import com.drawe.backend.domain.llm.repository.LlmMessageRepository;
import com.drawe.backend.domain.log.SearchLogService;
import com.drawe.backend.domain.onboarding.UserPrefSummaryService;
import com.drawe.backend.domain.project.repository.ProjectRepository;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchRequest;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.domain.search.service.SearchService;
import com.drawe.backend.global.config.LlmProperties;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
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

  private final KeywordExtractor keywordExtractor;
  private final SearchService searchService;
  private final SearchLogService searchLogService;
  private final UserPrefSummaryService userPrefSummaryService;
  private final AnalyticsEventService analyticsEventService;

  @Transactional
  public ChatResponse chat(User user, Long projectId, ChatRequest request) {
    Project project = loadProjectAuthorized(user, projectId);
    boolean isNewSession = (request.sessionId() == null || request.sessionId().isBlank());
    ChatSession session = resolveOrCreateSession(user, project, request.sessionId());

    if (isNewSession) {
      analyticsEventService.track(
          AnalyticsEventType.CHAT_START, user, session.getId(), Map.of("project_id", projectId));
    }

    ImageInputResolver.Resolved image = imageInputResolver.resolve(user, request.imageUrl());

    List<LlmMessage> all = llmMessageRepository.findByChatSessionOrderByCreatedAtAsc(session);
    List<LlmCallContext.Turn> history = trimHistory(all, llmProperties.getMaxHistory());

    ExtractionResult decision = keywordExtractor.extract(request.message(), history);
    List<ImageResult> references =
        handleSearchDecision(user, project, session.getId(), request.message(), decision);

    if (!references.isEmpty()) {
      String referenceContext = buildReferenceContext(references);
      history.add(new LlmCallContext.Turn(MessageRole.SYSTEM, referenceContext));
    } else {
      history.add(
          new LlmCallContext.Turn(
              MessageRole.SYSTEM,
              "[참고 이미지 안내]\n"
                  + "이번 답변에는 새로 검색된 참고 이미지가 없습니다.\n"
                  + "사용자의 질문에 직접 답변하세요.\n"
                  + "[1], [2] 같은 이미지 인용 표현을 사용하지 마세요.\n"
                  + "이전 대화에서 언급된 이미지가 있다면 그것만 참고 가능하지만, "
                  + "새 인용을 만들지 마세요."));
    }

    LlmProvider provider = resolveProvider(user);
    LlmService llm = pickService(provider);
    LlmCallContext ctx =
        new LlmCallContext(history, request.message(), image.bytes(), image.mimeType());

    LlmMessage userMsg = new LlmMessage();
    userMsg.setChatSession(session);
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent(request.message());
    userMsg.setHasImage(image.hasImage());
    userMsg.setImageUrl(image.storedUrl());
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

      List<ChatResponse.ReferenceItem> refItems = convertToReferenceItems(references);
      if (!refItems.isEmpty()) {
        assistantMsg.setReferences(refItems);
      }

      llmMessageRepository.save(assistantMsg);
      session.setLastActive(Instant.now());

      Map<String, Object> successPayload = new HashMap<>();
      successPayload.put("latency_ms", result.latencyMs());
      successPayload.put(
          "response_length", result.content() != null ? result.content().length() : 0);
      successPayload.put("provider", provider.name());
      successPayload.put("model", result.model());
      successPayload.put("reference_count", refItems.size());
      successPayload.put("has_image_input", image.hasImage());
      analyticsEventService.track(
          AnalyticsEventType.CHAT_SUCCESS, user, session.getId(), successPayload);

      return new ChatResponse(
          session.getId(),
          "guide",
          result.content(),
          convertToReferenceItems(references),
          decision.action().name(),
          null);
    } catch (CustomException e) {
      persistFailure(assistantMsg, e);
      trackError(user, session.getId(), provider, e);
      throw e;
    } catch (Exception e) {
      log.error(
          "LLM 호출 실패 session={} provider={} error_class={}",
          session.getId(),
          provider,
          e.getClass().getSimpleName());
      persistFailure(assistantMsg, e);
      trackError(user, session.getId(), provider, e);
      throw new CustomException(ErrorCode.AI_SERVICE_ERROR);
    }
  }

  private void trackError(User user, String sessionId, LlmProvider provider, Exception e) {
    Map<String, Object> errorPayload = new HashMap<>();
    errorPayload.put("error_class", e.getClass().getSimpleName());
    errorPayload.put(
        "error_code",
        e instanceof CustomException ce ? ce.getErrorCode().name() : "AI_SERVICE_ERROR");
    errorPayload.put("provider", provider != null ? provider.name() : "unknown");
    analyticsEventService.track(AnalyticsEventType.CHAT_ERROR, user, sessionId, errorPayload);
  }

  private List<ImageResult> handleSearchDecision(
      User user, Project project, String sessionId, String message, ExtractionResult decision) {

    int messageLength = message != null ? message.length() : 0;

    switch (decision.action()) {
      case NEW_SEARCH:
        try {
          SearchResponse result = searchService.search(new SearchRequest(decision.keywords(), 10));
          searchLogService.log(
              user, project, message, decision.keywords(), result.results(), "rag_chat");

          double avgScore =
              result.results().stream()
                  .mapToDouble(r -> r.score().doubleValue())
                  .average()
                  .orElse(0.0);

          double maxScore =
              result.results().stream().mapToDouble(r -> r.score().doubleValue()).max().orElse(0.0);

          double minScore =
              result.results().stream().mapToDouble(r -> r.score().doubleValue()).min().orElse(0.0);

          log.info("========== 검색 분석 ==========");
          log.info("user_id: {}", user.getId());
          log.info("session_id: {}", sessionId);
          log.info("extracted_keywords: {}", decision.keywords());
          log.info(
              "score_stats: avg={}, max={}, min={}, count={}",
              String.format("%.3f", avgScore),
              String.format("%.3f", maxScore),
              String.format("%.3f", minScore),
              result.results().size());

          for (int i = 0; i < result.results().size(); i++) {
            ImageResult r = result.results().get(i);
            log.info(
                "  [{}] score={}, technique={}, subject={}, mood={}",
                i + 1,
                String.format("%.3f", r.score()),
                r.technique(),
                r.subject(),
                r.mood());
          }

          Map<String, Object> searchPayload = new HashMap<>();
          searchPayload.put("keyword", decision.keywords());
          searchPayload.put("message_length", messageLength);
          searchPayload.put("result_count", result.results().size());
          searchPayload.put("avg_score", round3(avgScore));
          searchPayload.put("max_score", round3(maxScore));
          searchPayload.put("min_score", round3(minScore));

          if (avgScore < 0.2 || maxScore < 0.22) {
            log.warn(
                "❌ 무관 결과 판단: 검색 결과 차단 (avg={} < 0.2 || max={} < 0.22)",
                String.format("%.3f", avgScore),
                String.format("%.3f", maxScore));
            log.info("================================");

            searchPayload.put("blocked", true);
            searchPayload.put("blocked_reason", "low_score");
            analyticsEventService.track(
                AnalyticsEventType.SEARCH_BLOCKED, user, sessionId, searchPayload);
            return List.of();
          }

          log.info("✅ 유효 결과: {}개 references 반환", result.results().size());
          log.info("================================");

          searchPayload.put("blocked", false);
          analyticsEventService.track(
              AnalyticsEventType.SEARCH_EXECUTED, user, sessionId, searchPayload);
          return result.results();

        } catch (Exception e) {
          log.error(
              "검색 실패: keywords={}, error_class={}",
              decision.keywords(),
              e.getClass().getSimpleName());

          Map<String, Object> errorPayload = new HashMap<>();
          errorPayload.put("keyword", decision.keywords() != null ? decision.keywords() : "");
          errorPayload.put("message_length", messageLength);
          errorPayload.put("blocked", true);
          errorPayload.put("blocked_reason", "exception");
          errorPayload.put("error_class", e.getClass().getSimpleName());
          errorPayload.put(
              "error_code",
              e instanceof CustomException ce ? ce.getErrorCode().name() : "SEARCH_FAILED");
          analyticsEventService.track(
              AnalyticsEventType.SEARCH_BLOCKED, user, sessionId, errorPayload);
          return List.of();
        }

      case KEEP:
        log.info("⏸️  KEEP — 이전 references 유지 (session={})", sessionId);
        analyticsEventService.track(
            AnalyticsEventType.DECISION_KEEP,
            user,
            sessionId,
            Map.of("message_length", messageLength));
        return List.of();

      case SKIP:
        log.info("⏭️  SKIP — 검색 불필요 (session={})", sessionId);
        analyticsEventService.track(
            AnalyticsEventType.DECISION_SKIP,
            user,
            sessionId,
            Map.of("message_length", messageLength));
        return List.of();

      default:
        return List.of();
    }
  }

  private double round3(double v) {
    return Math.round(v * 1000.0) / 1000.0;
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

    LlmMessage persona = new LlmMessage();
    persona.setChatSession(session);
    persona.setRole(MessageRole.SYSTEM);
    persona.setContent(personaRegistry.resolve(PersonaRegistry.DEFAULT_KEY));
    persona.setHasImage(false);
    llmMessageRepository.save(persona);

    String userPrefs = userPrefSummaryService.buildSummary(user);
    if (!userPrefs.isBlank()) {
      LlmMessage prefsMsg = new LlmMessage();
      prefsMsg.setChatSession(session);
      prefsMsg.setRole(MessageRole.SYSTEM);
      prefsMsg.setContent(userPrefs);
      prefsMsg.setHasImage(false);
      llmMessageRepository.save(prefsMsg);
      log.info(
          "세션 생성 시 사용자 선호 인젝션: userId={}, sessionId={}, prefsLength={}",
          user.getId(),
          session.getId(),
          userPrefs.length());
    }

    String projectContext = buildProjectContext(project);
    if (projectContext != null) {
      LlmMessage context = new LlmMessage();
      context.setChatSession(session);
      context.setRole(MessageRole.SYSTEM);
      context.setContent(projectContext);
      context.setHasImage(false);
      llmMessageRepository.save(context);
    }
    return session;
  }

  private String buildProjectContext(Project project) {
    StringBuilder sb = new StringBuilder("[프로젝트 정보]\n");
    boolean any = false;
    if (notBlank(project.getName())) {
      sb.append("- 이름: ").append(project.getName()).append('\n');
      any = true;
    }
    if (notBlank(project.getSubject())) {
      sb.append("- 주제: ").append(project.getSubject()).append('\n');
      any = true;
    }
    if (notBlank(project.getTechnique())) {
      sb.append("- 스타일: ").append(project.getTechnique()).append('\n');
      any = true;
    }
    if (notBlank(project.getMood())) {
      sb.append("- 분위기: ").append(project.getMood()).append('\n');
      any = true;
    }
    if (notBlank(project.getDescription())) {
      sb.append("- 메모: ").append(project.getDescription()).append('\n');
      any = true;
    }
    return any ? sb.toString() : null;
  }

  private boolean notBlank(String s) {
    return s != null && !s.isBlank();
  }

  private LlmProvider resolveProvider(User user) {
    UserPlan plan = user.getPlan();
    if (plan == UserPlan.PAID) {
      return LlmProvider.CLAUDE;
    }
    return LlmProvider.GROK;
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

  private String buildReferenceContext(List<ImageResult> references) {
    StringBuilder sb = new StringBuilder();
    sb.append("[참고 이미지]\n");
    sb.append("아래는 사용자 질문과 관련하여 검색된 참고 이미지들입니다. ")
        .append("응답할 때 자연스럽게 이 이미지들을 [1], [2] 같은 형식으로 인용해주세요.\n\n");

    for (int i = 0; i < references.size(); i++) {
      ImageResult ref = references.get(i);
      sb.append("[").append(i + 1).append("] ");
      sb.append("유사도: ").append(String.format("%.2f", ref.score()));

      if (ref.technique() != null || ref.subject() != null || ref.mood() != null) {
        sb.append(" (");
        if (ref.technique() != null) {
          sb.append(", 기법: ").append(ref.technique());
        }
        if (ref.subject() != null) {
          sb.append(", 주제: ").append(ref.subject());
        }
        if (ref.mood() != null) {
          sb.append(", 분위기: ").append(ref.mood());
        }
        sb.append(")");
      }
      sb.append("\n");

      if (ref.utility() != null && !ref.utility().isEmpty()) {
        sb.append("    용도: ").append(String.join(", ", ref.utility())).append("\n");
      }

      if (ref.rawTags() != null && !ref.rawTags().isEmpty()) {
        String topTags = ref.rawTags().stream().limit(10).collect(Collectors.joining(", "));
        sb.append("    태그: ").append(topTags).append("\n");
      }
    }

    sb.append("\n응답 가이드:\n");
    sb.append("- 위 참고 이미지를 자연스럽게 언급하며 답변하세요.\n");
    sb.append("- 예: \"[1]번 이미지처럼 부드러운 색감을 표현하려면...\"\n");
    sb.append("- 모든 이미지를 다 언급할 필요는 없습니다. 관련 있는 것만 인용하세요.\n");
    sb.append("- 태그 정보를 활용해 구체적인 조언을 해주세요.\n");

    return sb.toString();
  }

  private List<ChatResponse.ReferenceItem> convertToReferenceItems(List<ImageResult> results) {
    return results.stream()
        .map(
            r ->
                new ChatResponse.ReferenceItem(
                    r.id(),
                    r.url(),
                    r.photographerName(),
                    r.photographerUsername(),
                    r.technique(),
                    r.subject(),
                    r.mood(),
                    r.score().doubleValue(),
                    r.source()))
        .toList();
  }
}
