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
import com.drawe.backend.domain.image.service.ImageUrlSigner;
import com.drawe.backend.domain.llm.classifier.IntentResultAdapter;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.dto.*;
import com.drawe.backend.domain.llm.metrics.LlmMetrics;
import com.drawe.backend.domain.llm.workflow.WorkflowService;
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
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

  private final RulePreRouter rulePreRouter;
  private final KeywordExtractor keywordExtractor;
  private final IntentResultAdapter intentResultAdapter;
  private final WorkflowService workflowService;
  private final LlmMetrics llmMetrics;
  private final MeterRegistry meterRegistry;
  private final SearchService searchService;
  private final SearchLogService searchLogService;
  private final ImageGenerationService imageGenerationService;
  private final UserPrefSummaryService userPrefSummaryService;
  private final AnalyticsEventService analyticsEventService;
  private final ImageUrlSigner imageUrlSigner;

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

    // 검색 결정: 결정론적 룰 프리라우터 먼저 → 미스면 Grok 풀 분류로 폴백.
    // 명확한 기능 신호(인사·감사·명시적 생성)는 LLM 콜 없이 룰로 끝낸다 (S1' 트랙 A).
    RoutedIntent routed = routeIntent(user, session.getId(), request.message(), history);
    ExtractionResult decision = routed.decision();

    // 사용자가 명시적으로 이미지 생성을 요청한 경우 — 검색·LLM 답변 모두 건너뛰고
    // 바로 Bria 호출해서 응답에 생성된 이미지 url 을 담아 돌려준다.
    if (decision.action() == ExtractionResult.Action.GENERATE_NOW) {
      return handleGenerateNow(user, project, session, request, decision);
    }

    List<ImageResult> references =
        handleSearchDecision(user, project, session.getId(), request.message(), routed);

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

    long llmStart = System.nanoTime();
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
      // LLM 내부 측정 latency 를 그대로 Timer 에 (외부 nanoTime 보다 정확).
      llmMetrics.llmCall(provider.name(), Duration.ofMillis(result.latencyMs()), true);

      return new ChatResponse(
          session.getId(),
          "guide",
          result.content(),
          signReferenceUrls(refItems),
          decision.action().name(), // "NEW_SEARCH" | "KEEP" | "SKIP"
          offerGenerate,
          offerGenerate ? request.message() : null,
          null);
    } catch (CustomException e) {
      llmMetrics.llmCall(provider.name(), Duration.ofNanos(System.nanoTime() - llmStart), false);
      persistFailure(assistantMsg, e);
      trackError(user, session.getId(), provider, e);
      throw e;
    } catch (Exception e) {
      llmMetrics.llmCall(provider.name(), Duration.ofNanos(System.nanoTime() - llmStart), false);
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

  /**
   * 의도 분류: 결정론적 룰 프리라우터를 먼저 시도하고, 미스면 Grok 풀 분류로 폴백한다.
   *
   * <p>룰 히트/미스를 analytics(DB) + Micrometer(실시간) 로 집계해 ADR §4 DoD(룰 적중률 ≥ 30%, 분류 latency ≤
   * 300ms) 를 측정한다.
   */
  /**
   * 분류 결과 + 어느 tier 가 결정했는지. {@code ruleDecided}=true 면 룰(RulePreRouter), false 면 Grok 폴백. shadow
   * 워크플로우의 IntentResult tier 판정에 쓴다.
   */
  private record RoutedIntent(ExtractionResult decision, boolean ruleDecided) {}

  private RoutedIntent routeIntent(
      User user, String sessionId, String message, List<LlmCallContext.Turn> history) {
    RulePreRouter.Decision ruleDecision = rulePreRouter.route(message, history);

    if (ruleDecision.isHit()) {
      String action = ruleDecision.result().action().name();
      Map<String, Object> payload = new HashMap<>();
      payload.put("rule_id", ruleDecision.ruleId());
      payload.put("action", action);
      analyticsEventService.track(AnalyticsEventType.INTENT_RULE_HIT, user, sessionId, payload);
      llmMetrics.ruleHit(ruleDecision.ruleId(), action);
      return new RoutedIntent(ruleDecision.result(), true);
    }

    analyticsEventService.track(
        AnalyticsEventType.INTENT_RULE_MISS,
        user,
        sessionId,
        Map.of("message_length", message != null ? message.length() : 0));
    llmMetrics.ruleMiss();

    // 룰 미스 → 경량 분류기(Grok) 호출. latency 를 Timer 로 측정 (DoD ≤300ms).
    long start = System.nanoTime();
    boolean success = false;
    try {
      ExtractionResult result = keywordExtractor.extract(message, history);
      success = true;
      return new RoutedIntent(result, false);
    } finally {
      llmMetrics.classifyLatency(Duration.ofNanos(System.nanoTime() - start), success);
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
      User user, Project project, String sessionId, String message, RoutedIntent routed) {

    ExtractionResult decision = routed.decision();
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

          if (avgScore < 0.2 || maxScore < 0.21) {
            log.warn(
                "❌ 무관 결과 판단: 검색 결과 차단 (avg={} < 0.2 || max={} < 0.21)",
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

          // shadow: WorkflowService(Komoran 경로)를 병렬로 한 번 돌려 기존(Grok 키워드) 검색결과와
          // 비교만 한다. 실제 응답에는 영향 없음 (트랙 A ③ shadow 연결).
          shadowWorkflow(user, project, sessionId, message, routed, result.results());

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

  /**
   * shadow 워크플로우 (트랙 A ③). 기존 chat() 검색 결과는 그대로 두고, WorkflowService(Komoran 경로)를 병렬로 한 번
   * 돌려 같은 입력에 어떤 검색 결과를 냈을지 비교·로깅·메트릭만 한다. **실제 응답에는 영향이 없으며 예외도 절대 밖으로 던지지 않는다.**
   *
   * <p>핵심 비교: 기존은 Grok 이 뽑은 영문 키워드로 검색, shadow 는 Komoran 형태소→사전 키워드로 검색. ref id 집합이
   * 얼마나 겹치는지(match/partial/miss)로 트랙 B 사전 품질을 검증한다. 설계: {@code
   * docs/decisions/S1A-workflow-shadow-design.md}.
   */
  private void shadowWorkflow(
      User user,
      Project project,
      String sessionId,
      String message,
      RoutedIntent routed,
      List<ImageResult> baselineResults) {
    try {
      IntentResult intent =
          intentResultAdapter.adapt(routed.decision(), routed.ruleDecided(), List.of(), false);

      // shadow 1차: rawMessage 를 그대로 cleanedMessage 로 (앵커 전처리는 ① 2차 몫).
      StepContext initial =
          StepContext.start(
              user.getId(),
              project.getId(),
              sessionId,
              message,
              message,
              intent,
              null,
              List.of());

      StepContext finalCtx = workflowService.run(intent, initial);

      // 기존(baseline) vs shadow 검색결과 ref id 비교.
      Set<Long> baseIds =
          baselineResults.stream().map(ImageResult::id).collect(Collectors.toSet());
      Set<Long> shadowIds =
          finalCtx.references().stream().map(ReferenceImage::imageId).collect(Collectors.toSet());

      String outcome = classifyShadowOutcome(baseIds, shadowIds);

      log.info(
          "🔬 shadow workflow: code={} outcome={} base_n={} shadow_n={} overlap={}",
          intent.code().code(),
          outcome,
          baseIds.size(),
          shadowIds.size(),
          shadowIds.stream().filter(baseIds::contains).count());
      meterRegistry.counter("drawe.workflow.shadow", "outcome", outcome).increment();
    } catch (Exception e) {
      // shadow 는 절대 실제 응답을 깨면 안 된다 — 어떤 예외도 삼키고 메트릭만.
      log.warn("shadow workflow 실패(무시): error_class={}", e.getClass().getSimpleName());
      meterRegistry.counter("drawe.workflow.shadow", "outcome", "error").increment();
    }
  }

  /**
   * baseline(Grok 키워드) vs shadow(Komoran 키워드) 검색결과 ref id 집합을 비교해 outcome 을 판정한다.
   *
   * <ul>
   *   <li>{@code match} — 두 집합이 정확히 같음 (shadow 가 baseline 을 완전 재현)</li>
   *   <li>{@code partial} — 교집합은 있으나 완전히 같지는 않음</li>
   *   <li>{@code miss} — shadow 가 비었거나 교집합이 전혀 없음</li>
   * </ul>
   *
   * <p>shadow 가 비었으면(키워드 추출/검색이 결과 0) baseline 과 무관하게 {@code miss}. {@code error}(예외)는
   * 호출 측 catch 가 별도로 찍으므로 여기서는 다루지 않는다. 순수 함수 — 테스트 용이성을 위해 분리.
   */
  static String classifyShadowOutcome(Set<Long> baseIds, Set<Long> shadowIds) {
    if (shadowIds.isEmpty()) {
      return "miss";
    }
    if (shadowIds.equals(baseIds)) {
      return "match";
    }
    if (shadowIds.stream().anyMatch(baseIds::contains)) {
      return "partial";
    }
    return "miss";
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
            .map(m -> ChatHistoryResponse.HistoryItem.from(m, imageUrlSigner))
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
        new ChatResponse.GeneratedImage(
            image.getId(), imageUrlSigner.sign(image.getUrl()), decision.keywords()));
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
        session.getId(), image.getId(), imageUrlSigner.sign(image.getUrl()), request.prompt());
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

  @Transactional(readOnly = true)
  public String getLatestSessionId(User user, Long projectId) {
    return chatSessionRepository
        .findTopByUserAndProjectIdOrderByLastActiveDesc(user, projectId)
        .map(ChatSession::getId)
        .orElse(null);
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

  /**
   * 응답으로 내보내기 직전 레퍼런스 이미지 URL 에 서명을 붙인다. DB 에는 상대경로({@code /images/{id}})로 저장하고 (만료가 박힌 URL 을
   * 영구 저장하지 않기 위해) 노출 순간에만 서명한다. Unsplash 절대 URL 은 signer 가 그대로 통과시킨다.
   */
  private List<ChatResponse.ReferenceItem> signReferenceUrls(
      List<ChatResponse.ReferenceItem> items) {
    return items.stream()
        .map(
            r ->
                new ChatResponse.ReferenceItem(
                    r.id(),
                    imageUrlSigner.sign(r.url()),
                    r.photographerName(),
                    r.photographerUsername(),
                    r.technique(),
                    r.subject(),
                    r.mood(),
                    r.similarity(),
                    r.source()))
        .toList();
  }
}
