package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.domain.llm.metrics.LlmMetrics;
import com.drawe.backend.domain.llm.output.ComposedOutput;
import com.drawe.backend.domain.llm.output.IntegrityResult;
import com.drawe.backend.domain.llm.output.OutputIntegrityChecker;
import com.drawe.backend.domain.llm.output.OutputParser;
import com.drawe.backend.domain.llm.service.GrokService;
import com.drawe.backend.domain.llm.service.LlmService;
import com.drawe.backend.global.error.CustomException;
import com.drawe.backend.global.error.ErrorCode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * COMPOSE 단계 실행기 — 페르소나 + 레퍼런스 컨텍스트로 최종 가이드 응답을 LLM 으로 생성한다 (A 소유).
 *
 * <p><b>실연결(트랙 A ④)</b>: 기존 {@code ChatLlmService.chat()} 안에 흩어져 있던 LLM 합성 로직을 이관했다.
 * 책임 절단선 — 이 Executor 는 <b>순수 합성</b>(referenceContext 구성 → 스키마 강제 LLM 호출 → 파싱 → 무결성 검사)만
 * 떠안고, {@code ctx.withComposedOutput(...)} 로 결과를 돌려준다. 저장(LlmMessage)·analytics·메트릭·ChatResponse
 * 조립 같은 부수효과는 {@code ChatLlmService} 에 남는다(설계 §3.2).
 *
 * <p>입력은 {@link StepContext} 가 분류 단계에서 실어 온 {@code history}(persona·userPrefs SYSTEM turn 포함),
 * {@code references}(B 가 채운 검색 결과), 멀티모달 {@code uploadedImageBytes}, 그리고 {@code provider} 다.
 * COMPOSE 만 structured output 을 쓰므로 {@link GrokService#DRAW_GUIDE_SCHEMA_NAME} 을 실어 호출한다(②).
 */
@Slf4j
@Component
public class ComposeExecutor implements StepExecutor {

  /**
   * LLM 본문에 생성 안내 표현이 섞이면 offerGenerate 를 강제 노출(§6). 페르소나로 톤을 자제시켜도 가끔
   * "버튼으로 만들어드릴게요" 류가 나오는데, 본문은 약속하고 버튼은 안 뜨면 모순이 사용자에게 보인다.
   */
  private static final Pattern GENERATE_OFFER_PATTERN =
      Pattern.compile(
          "(이미지\\s*생성)"
              + "|(생성\\s*버튼)"
              + "|(만들어\\s*드릴게요)"
              + "|(만들어드릴게요)"
              + "|(생성해\\s*드릴까요)"
              + "|(생성해드릴까요)"
              + "|(만들어\\s*드릴까요)"
              + "|(만들어드릴까요)",
          Pattern.CASE_INSENSITIVE);

  private final Map<LlmProvider, LlmService> llmServices;
  private final OutputParser outputParser;
  private final OutputIntegrityChecker integrityChecker;
  private final LlmMetrics llmMetrics;

  public ComposeExecutor(
      List<LlmService> llmServices,
      OutputParser outputParser,
      OutputIntegrityChecker integrityChecker,
      LlmMetrics llmMetrics) {
    Map<LlmProvider, LlmService> map = new EnumMap<>(LlmProvider.class);
    for (LlmService s : llmServices) {
      map.put(s.provider(), s);
    }
    this.llmServices = map;
    this.outputParser = outputParser;
    this.integrityChecker = integrityChecker;
    this.llmMetrics = llmMetrics;
  }

  @Override
  public StepType type() {
    return StepType.COMPOSE;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    if (ctx.composedOutput() != null) {
      return ctx;
    }

    // KEEP 멀티턴 단기메모리: 이번 턴 검색이 없으면(references 비었음) 직전 턴의 레퍼런스를 재사용한다.
    // IntentRouting 이 KEEP → [COMPOSE] 만 돌려 SearchExecutor 를 건너뛰면 ctx.references() 가 빈 리스트인데,
    // 그대로 두면 "참고 이미지 없음" 안내가 나가 멀티턴 맥락이 끊긴다. chatViaWorkflow 가 Redis(getOrRestore)
    // 로 실어 보낸 ctx.previousReferences() 를 여기서 LLM 컨텍스트·무결성 기준으로 동등하게 쓴다(SCRUM-88 배선).
    // 단 이 refs 는 응답 노출용이 아니라 합성 컨텍스트용 — 응답 refItems 는 chatViaWorkflow 가 별도로 결정한다.
    List<ReferenceImage> refs =
        ctx.references().isEmpty() ? ctx.previousReferences() : ctx.references();

    // 1. references → referenceContext SYSTEM turn 으로 변환해 누적 history 끝에 붙인다(§3.2).
    //    references 가 비면 "참고 없음" 안내 turn 을 붙여 LLM 이 가짜 인용·가짜 결과를 만들지 않게 한다.
    IntentCode code = ctx.intent() == null ? null : ctx.intent().code();
    List<LlmCallContext.Turn> history = new ArrayList<>(ctx.history());
    history.add(new LlmCallContext.Turn(MessageRole.SYSTEM, buildReferenceContext(refs, code)));

    // 2. 스키마 강제 LLM 호출 — COMPOSE 만 structured output(draw_guide_response)을 쓴다(②).
    LlmCallContext callContext =
        new LlmCallContext(
            history,
            ctx.rawMessage(),
            ctx.uploadedImageBytes(),
            ctx.uploadedImageMimeType(),
            GrokService.DRAW_GUIDE_SCHEMA_NAME);

    LlmService llm = pickService(ctx.provider());
    LlmCallResult result = llm.generate(callContext);

    // 3. 파싱(깨진 JSON → 안전 템플릿 폴백, 재호출 없음) → 결정론적 무결성 검사(환각 인용 제거).
    OutputParser.ParsedOutput parsed = outputParser.parseWithSignal(result.content());
    IntegrityResult integrity = integrityChecker.check(parsed.output(), refs);
    ComposedOutput corrected = integrity.output();

    // 4. 본문에 생성 안내 표현이 있으면 offerGenerate 강제 노출(§6).
    ComposedOutput finalOutput = applyGenerateOfferHint(corrected);

    // 5. DoD 메트릭(§5.2). provider 태그는 유한 열거값(GROK/CLAUDE/GEMINI).
    String providerTag = ctx.provider() == null ? "unknown" : ctx.provider().name();
    if (parsed.brokenJson()) {
      // 깨진 JSON → 안전 템플릿으로 평문화됨 = 구조 위반. (스키마 거부는 LLM 호출 단의 4xx 경로가 별도로 흡수.)
      llmMetrics.structureViolation(providerTag, "json_broke");
    }
    emitHallucinationMetrics(integrity);

    if (integrity.hadHallucination()) {
      log.info(
          "COMPOSE 무결성 정정: refs={}, citations밖={}, 본문토큰밖={}, no_refs={}",
          refs.size(),
          integrity.hallucinatedCitations(),
          integrity.hallucinatedBodyTokens(),
          integrity.noRefs());
    }

    // LLM 콜 트랜스포트 메타(model·latency)를 진실의 원천 옆 슬롯에 옮겨 담는다(⑤). 레거시 경로가
    // result.model()/result.latencyMs() 로 assistantMsg·llmMetrics 를 채우던 것을 live 경로에서 재현하기 위함.
    return ctx.withComposedOutput(finalOutput)
        .withComposedAnswer(finalOutput.message())
        .withComposeModel(result.model())
        .withComposeLatencyMs(result.latencyMs());
  }

  /**
   * 환각 인용 메트릭 발사(§5.2, DoD 0건). source 분류: refs 가 비어 있었으면({@code no_refs}) citations·본문
   * 위반을 모두 {@code no_refs} 로 합산한다(참고가 0인데 인용한 것이라 출처 구분이 무의미). refs 가 있었으면
   * citations 슬롯 범위밖 = {@code citations_field}, 본문 [N] 범위밖 = {@code body_scan}. 제거 총수는
   * 관측용 {@code citation_removed} 로 별도 발사. count 0 인 발사는 메서드 쪽에서 무시한다.
   */
  private void emitHallucinationMetrics(IntegrityResult integrity) {
    if (integrity.noRefs()) {
      llmMetrics.hallucinatedCitation("no_refs", integrity.totalRemoved());
    } else {
      llmMetrics.hallucinatedCitation("citations_field", integrity.hallucinatedCitations());
      llmMetrics.hallucinatedCitation("body_scan", integrity.hallucinatedBodyTokens());
    }
    llmMetrics.citationRemoved(integrity.totalRemoved());
  }

  /** 본문에 생성 안내 표현이 있고 아직 offerGenerate=false 면 true 로 올린 새 DTO 를 반환한다. */
  private ComposedOutput applyGenerateOfferHint(ComposedOutput output) {
    if (output.offerGenerate()) {
      return output;
    }
    String message = output.message();
    if (message != null && GENERATE_OFFER_PATTERN.matcher(message).find()) {
      log.info("LLM 답변에 생성 안내 표현 감지 → offerGenerate 강제 true");
      return new ComposedOutput(message, output.citations(), true);
    }
    return output;
  }

  private LlmService pickService(LlmProvider provider) {
    LlmService service = provider == null ? null : llmServices.get(provider);
    if (service == null) {
      log.error("COMPOSE provider 에 해당하는 LlmService 없음: provider={}", provider);
      throw new CustomException(ErrorCode.INVALID_INPUT);
    }
    return service;
  }

  /**
   * references → SYSTEM turn 본문. {@link ReferenceImage} 기준으로 1-based 인덱스·점수·태그를 나열하고
   * 인용 규칙([N])과 가짜 결과 금지 가이드를 동봉한다. references 가 비면 "참고 없음" 안내로 대체한다.
   */
  private String buildReferenceContext(List<ReferenceImage> references, IntentCode code) {
    if (references.isEmpty()) {
      // 012 FOLLOWUP — references 가 없는 건 "검색 실패"가 아니라 "검색을 안 한" 것이다. 직전 답변에 대한
      // 부연·재설명·평가 요청이므로 'AI 생성 권유'(아래 기본 안내)를 주면 베타 오답이 그대로 재현된다
      // (레거시 경로의 FOLLOWUP_GUIDE 와 동일 정신). 직전 답변을 이어서 풀어주는 톤으로 못박는다.
      if (code == IntentCode.FOLLOWUP) {
        return "[후속 질문 안내]\n"
            + "이번 발화는 방금 당신(어시스턴트)이 한 답변에 대한 부연·재설명·평가 요청입니다.\n"
            + "(예: \"더 설명\", \"말로 설명해\", \"어때?\", \"그 외는?\", \"왜 그렇게 해?\")\n"
            + "\n"
            + "응답 가이드:\n"
            + "- 새 주제로 넘어가지 말고, 바로 직전 답변을 이어서 더 구체적으로 풀어주세요.\n"
            + "- \"말로 설명\"·\"피드백해줘\"처럼 평가를 원하면, 회피하지 말고 솔직하고 구체적으로 답하세요.\n"
            + "- 한두 문장으로 핵심을 더하거나, 직전에 말한 부분을 다른 말로 다시 설명해 주세요.\n"
            + "\n"
            + "금지:\n"
            + "- \"자료가 부족한 것 같아요. AI 이미지로 생성해드릴까요?\" 류의 회피·생성 권유 (사용자는 '말'을 원함).\n"
            + "- [1], [2] 같은 인용 표현 (참고 이미지 없음).\n"
            + "- \"잠시만요\", \"어떤 부분이요?\"처럼 되묻기만 하고 답을 미루는 표현.";
      }
      if (code == IntentCode.COMPARE) {
        // 013 COMPARE — references 가 비었으면 비교 대상이 직전 대화(앞서 보여준 레퍼런스·옵션)에 있다는 뜻이다.
        // FOLLOWUP 과 같은 정신: '검색 실패'가 아니므로 'AI 생성 권유' 기본 안내를 주면 베타 오답이 재현된다.
        // 이미 맥락에 있는 대상을 비교·대조해 설명하는 톤으로 못박는다.
        return "[비교 안내]\n"
            + "이번 발화는 이미 대화에 나온 대상(앞서 보여준 참고 이미지·옵션·직전 답변에서 언급한 것들)을\n"
            + "비교·대조해 달라는 요청입니다. (예: \"1번이랑 2번 중 뭐가 나아?\", \"둘 차이가 뭐야?\")\n"
            + "\n"
            + "응답 가이드:\n"
            + "- 새로 검색하거나 만들지 말고, 이미 맥락에 있는 대상들을 짚어 차이점·장단점을 구체적으로 비교하세요.\n"
            + "- 구도·명암·색감·기법 등 미술적 관점에서 각각의 특징과 어떤 상황에 어느 쪽이 나은지 설명하세요.\n"
            + "- 한쪽으로 치우치지 말고, 사용자의 목적(예: 초보/분위기)을 고려해 균형 있게 판단을 더하세요.\n"
            + "\n"
            + "금지:\n"
            + "- \"자료가 부족한 것 같아요. AI 이미지로 생성해드릴까요?\" 류의 회피·생성 권유 (비교를 원함).\n"
            + "- [1], [2] 같은 인용 표현 (지금 새로 검색된 참고 이미지가 없음 — 직전 맥락의 대상만 말로 지칭).\n"
            + "- \"어떤 걸 비교할까요?\"처럼 되묻기만 하고 비교를 미루는 표현.";
      }
      return "[참고 이미지 안내]\n"
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
          + "- \"잠시만요\", \"어떤 분위기·구도\"처럼 길게 되묻거나 약속을 늘이지 마세요.";
    }

    StringBuilder sb = new StringBuilder();
    sb.append("[참고 이미지]\n");
    sb.append("아래는 사용자 질문과 관련하여 검색된 참고 이미지들입니다. ")
        .append("응답할 때 자연스럽게 이 이미지들을 [1], [2] 같은 형식으로 인용해주세요.\n\n");

    for (ReferenceImage ref : references) {
      sb.append("[").append(ref.index()).append("] ");
      sb.append("유사도: ").append(ref.score() == null ? "N/A" : ref.score().toPlainString());
      if (ref.photographer() != null && !ref.photographer().isBlank()) {
        sb.append(" (작가: ").append(ref.photographer()).append(")");
      }
      sb.append("\n");
      if (!ref.tags().isEmpty()) {
        String topTags = String.join(", ", ref.tags().subList(0, Math.min(10, ref.tags().size())));
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
}
