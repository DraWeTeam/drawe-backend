package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
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

  public ComposeExecutor(
      List<LlmService> llmServices,
      OutputParser outputParser,
      OutputIntegrityChecker integrityChecker) {
    Map<LlmProvider, LlmService> map = new EnumMap<>(LlmProvider.class);
    for (LlmService s : llmServices) {
      map.put(s.provider(), s);
    }
    this.llmServices = map;
    this.outputParser = outputParser;
    this.integrityChecker = integrityChecker;
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

    List<ReferenceImage> refs = ctx.references();

    // 1. references → referenceContext SYSTEM turn 으로 변환해 누적 history 끝에 붙인다(§3.2).
    //    references 가 비면 "참고 없음" 안내 turn 을 붙여 LLM 이 가짜 인용·가짜 결과를 만들지 않게 한다.
    List<LlmCallContext.Turn> history = new ArrayList<>(ctx.history());
    history.add(new LlmCallContext.Turn(MessageRole.SYSTEM, buildReferenceContext(refs)));

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
    ComposedOutput parsed = outputParser.parse(result.content());
    IntegrityResult integrity = integrityChecker.check(parsed, refs);
    ComposedOutput corrected = integrity.output();

    // 4. 본문에 생성 안내 표현이 있으면 offerGenerate 강제 노출(§6).
    ComposedOutput finalOutput = applyGenerateOfferHint(corrected);

    if (integrity.hadHallucination()) {
      log.info(
          "COMPOSE 무결성 정정: refs={}, citations밖={}, 본문토큰밖={}",
          refs.size(),
          integrity.hallucinatedCitations(),
          integrity.hallucinatedBodyTokens());
    }

    return ctx.withComposedOutput(finalOutput).withComposedAnswer(finalOutput.message());
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
  private String buildReferenceContext(List<ReferenceImage> references) {
    if (references.isEmpty()) {
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
