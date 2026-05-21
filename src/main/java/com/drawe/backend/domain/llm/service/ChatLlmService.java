package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.ChatSession;
import com.drawe.backend.domain.Image;
import com.drawe.backend.domain.LlmMessage;
import com.drawe.backend.domain.Project;
import com.drawe.backend.domain.User;
import com.drawe.backend.domain.analytics.AnalyticsEventType;
import com.drawe.backend.domain.analytics.service.AnalyticsEventService;
import com.drawe.backend.domain.enums.LlmCallStatus;
import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.enums.UserPlan;
import com.drawe.backend.domain.image.service.ImageGenerationService;
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
  private final ImageGenerationService imageGenerationService;
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

    // 검색 결정
    ExtractionResult decision = keywordExtractor.extract(request.message(), history);

    // 사용자가 명시적으로 이미지 생성을 요청한 경우 — 검색·LLM 답변 모두 건너뛰고
    // 바로 Bria 호출해서 응답에 생성된 이미지 url 을 담아 돌려준다.
    if (decision.action() == ExtractionResult.Action.GENERATE_NOW) {
      return handleGenerateNow(user, project, session, request, decision);
    }

    List<ImageResult> references =
        handleSearchDecision(user, project, session.getId(), request.message(), decision);

    // 검색은 시도했지만 적합한 레퍼런스가 없을 때 AI 이미지 생성을 제안한다.
    boolean offerGenerate =
        decision.action() == ExtractionResult.Action.NEW_SEARCH && references.isEmpty();

    if (!references.isEmpty()) {
      String referenceContext = buildReferenceContext(references);
      history.add(new LlmCallContext.Turn(MessageRole.SYSTEM, referenceContext));
    } else {
      // 레퍼런스가 없을 때:
      // 답변은 짧고 단정한 한 줄로 — "자료가 부족한 것 같아요. AI 이미지로 생성해드릴까요?" 류.
      // 시스템이 이 답변과 함께 생성 버튼을 자동 노출한다 (offerGenerate=true).
      // LLM 본인이 이미지를 만든 척하는 표현은 금지.
      history.add(
          new LlmCallContext.Turn(
              MessageRole.SYSTEM,
              "[참고 이미지 안내]\n"
                  + "이번 답변에는 검색된 참고 이미지가 없습니다.\n"
                  + "\n"
                  + "응답 가이드:\n"
                  + "- 한 줄로 짧게 안내하세요. 예: \"자료가 좀 부족한 것 같아요. AI 이미지로 생성해드릴까요?\"\n"
                  + "- 사용자가 이미지/레퍼런스를 원했다면 위 톤으로 마무리하면 됩니다.\n"
                  + "- 시스템이 이 답변에 'AI 이미지 생성' 버튼을 자동으로 노출합니다.\n"
                  + "\n"
                  + "금지:\n"
                  + "- [1], [2] 같은 인용 표현 (지금 참고 이미지가 없음).\n"
                  + "- 네가 만들지 않은 이미지를 만든 척하는 표현:\n"
                  + "  \"만들어왔어요\", \"만들어드렸어요\", \"준비해봤어요\", \"여기 이미지요\" 등.\n"
                  + "- \"잠시만요\", \"어떤 분위기·구도\"처럼 길게 되묻거나 약속을 늘이지 마세요."));
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

      // 답변 후처리: LLM 본문에 생성 안내 표현이 있으면 무조건 버튼 노출.
      // 페르소나로 톤은 자제시켜도 가끔 LLM이 "버튼으로 만들어드릴게요" 류 표현을 하는데,
      // 그러면 본문은 약속하고 버튼은 안 뜨는 모순이 사용자한테 보임.
      if (!offerGenerate && mentionsGenerateOffer(result.content())) {
        offerGenerate = true;
        log.info("LLM 답변에 생성 안내 표현 감지 → offerGenerate 강제 true: session={}", session.getId());
      }

      Map<String, Object> successPayload = new HashMap<>();
      successPayload.put("latency_ms", result.latencyMs());
      successPayload.put(
          "response_length", result.content() != null ? result.content().length() : 0);
      successPayload.put("provider", provider.name());
      successPayload.put("model", result.model());
      successPayload.put("reference_count", refItems.size());
      successPayload.put("has_image_input", image.hasImage());
      successPayload.put("offer_generate", offerGenerate);
      analyticsEventService.track(
          AnalyticsEventType.CHAT_SUCCESS, user, session.getId(), successPayload);

      return new ChatResponse(
          session.getId(),
          "guide",
          result.content(),
          convertToReferenceItems(references),
          decision.action().name(), // "NEW_SEARCH" | "KEEP" | "SKIP"
          offerGenerate,
          offerGenerate ? request.message() : null,
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
          log.info(
              "keywords_length: {}",
              decision.keywords() != null ? decision.keywords().length() : 0);
          log.info(
              "score_stats: avg={}, max={}, min={}, count={}",
              String.format("%.3f", avgScore),
              String.format("%.3f", maxScore),
              String.format("%.3f", minScore),
              result.results().size());

          for (int i = 0; i < result.results().size(); i++) {
            ImageResult r = result.results().get(i);
            log.info(
                "  [{}] id={}, score={}, technique={}, subject={}, mood={}",
                i + 1,
                r.id(),
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

          // 검색 결과 image_id 배열 (분석/디버깅용)
          List<Long> imageIds = result.results().stream().map(ImageResult::id).toList();
          searchPayload.put("image_ids", imageIds);

          // 점수도 같이 (소수점 3자리)
          List<Double> scores =
              result.results().stream()
                  .map(r -> Math.round(r.score().doubleValue() * 1000.0) / 1000.0)
                  .toList();
          searchPayload.put("scores", scores);

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
              "검색 실패: keywords_length={}, error_class={}",
              decision.keywords() != null ? decision.keywords().length() : 0,
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

  /**
   * GENERATE_NOW 분기 — 사용자가 명시적으로 "만들어줘"라고 했을 때 검색·LLM 답변을 건너뛰고 즉시 Bria 호출.
   *
   * <p>일반 chat 경로와 다른 점:
   *
   * <ul>
   *   <li>검색하지 않음 (사용자가 새 이미지를 원했음이 분명함)
   *   <li>LLM 답변 호출하지 않음 — 답변은 고정 문구로 대체 ("만들어왔어요" 할루시네이션 원천 차단)
   *   <li>ChatResponse.generatedImage 필드에 새 이미지 정보 포함
   * </ul>
   */
  private ChatResponse handleGenerateNow(
      User user,
      Project project,
      ChatSession session,
      ChatRequest request,
      ExtractionResult decision) {
    // 사용자 메시지 저장
    LlmMessage userMsg = new LlmMessage();
    userMsg.setChatSession(session);
    userMsg.setRole(MessageRole.USER);
    userMsg.setContent(request.message());
    userMsg.setHasImage(false);
    llmMessageRepository.save(userMsg);

    // KeywordExtractor가 추출한 영문 프롬프트로 즉시 생성.
    // ImageGenerationService 가 내부에서 또 한 번 번역하지만, 영문 입력이면 그대로 통과.
    Image image = imageGenerationService.generate(user, decision.keywords(), project);

    String assistantText = "요청하신 이미지를 만들어드렸어요. 마음에 드시면 이어서 작업해보시고, 다른 분위기로 바꿔드릴까요?";
    LlmMessage assistantMsg = new LlmMessage();
    assistantMsg.setChatSession(session);
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setContent(assistantText);
    assistantMsg.setHasImage(true);
    assistantMsg.setImageUrl(image.getUrl());
    assistantMsg.setStatus(LlmCallStatus.SUCCESS);
    llmMessageRepository.save(assistantMsg);

    session.setLastActive(Instant.now());

    log.info(
        "GENERATE_NOW 처리 완료: user={}, imageId={}, prompt_length={}",
        user.getId(),
        image.getId(),
        decision.keywords() != null ? decision.keywords().length() : 0);

    return new ChatResponse(
        session.getId(),
        "guide",
        assistantText,
        List.of(),
        decision.action().name(), // "GENERATE_NOW"
        false,
        null,
        new ChatResponse.GeneratedImage(image.getId(), image.getUrl(), decision.keywords()));
  }

  /** 사용자가 "AI 이미지 만들어주세요" 버튼을 누른 경우 호출. Bria 로 이미지 생성 후 세션에 ASSISTANT 메시지로 기록. */
  @Transactional
  public GenerateImageResponse generateImage(
      User user, Long projectId, String sessionId, GenerateImageRequest request) {
    Project project = loadProjectAuthorized(user, projectId);
    ChatSession session = loadSessionAuthorized(user, sessionId, projectId);

    Image image = imageGenerationService.generate(user, request.prompt(), project);

    LlmMessage assistantMsg = new LlmMessage();
    assistantMsg.setChatSession(session);
    assistantMsg.setRole(MessageRole.ASSISTANT);
    assistantMsg.setContent("AI 이미지를 생성했어요. 원하시면 추가 수정 방향을 알려주세요.");
    assistantMsg.setHasImage(true);
    assistantMsg.setImageUrl(image.getUrl());
    assistantMsg.setStatus(LlmCallStatus.SUCCESS);
    llmMessageRepository.save(assistantMsg);

    session.setLastActive(Instant.now());

    return new GenerateImageResponse(
        session.getId(), image.getId(), image.getUrl(), request.prompt());
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

  // 한글/영문 변형까지 묶어 한 번에 잡는다. 너무 좁으면 누락, 너무 넓으면 일반 대화에서 오탐.
  // 핵심 키워드: "생성" + "버튼", 또는 "만들어드릴" / "만들어 드릴" / "생성해드릴", "AI 이미지" + 동작어.
  private static final java.util.regex.Pattern GENERATE_OFFER_PATTERN =
      java.util.regex.Pattern.compile(
          "(AI\\s*이미지[^\\n]{0,15}생성)"
              + "|(생성\\s*버튼)"
              + "|(만들어\\s*드릴게요)"
              + "|(만들어드릴게요)"
              + "|(생성해\\s*드릴까요)"
              + "|(생성해드릴까요)"
              + "|(만들어\\s*드릴까요)"
              + "|(만들어드릴까요)",
          java.util.regex.Pattern.CASE_INSENSITIVE);

  private boolean mentionsGenerateOffer(String llmAnswer) {
    if (llmAnswer == null || llmAnswer.isBlank()) {
      return false;
    }
    return GENERATE_OFFER_PATTERN.matcher(llmAnswer).find();
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
    sb.append(
        "- 네가 직접 추가 이미지를 만들어왔다고 말하지 마세요 "
            + "(\"더 그려왔어요\", \"만들어둔 게 있어요\" 같은 가짜 결과 금지).\n"
            + "- 만약 사용자가 만족 못 하면 짧게 한 줄 안내: "
            + "\"원하시면 AI 이미지로 새로 생성해드릴까요?\" 정도.\n");

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
