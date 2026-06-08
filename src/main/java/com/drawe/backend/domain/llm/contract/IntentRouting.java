package com.drawe.backend.domain.llm.contract;

import static com.drawe.backend.domain.llm.contract.StepType.COMPOSE;
import static com.drawe.backend.domain.llm.contract.StepType.CRITIQUE_UPLOAD;
import static com.drawe.backend.domain.llm.contract.StepType.EXTRACT_KEYWORDS;
import static com.drawe.backend.domain.llm.contract.StepType.GENERATE_IMAGE;
import static com.drawe.backend.domain.llm.contract.StepType.SEARCH;
import static com.drawe.backend.domain.llm.contract.StepType.TRANSLATE;

import java.util.List;
import java.util.Map;

/**
 * 정적 라우팅 맵 — {@link IntentCode} → 실행할 {@link StepType} 시퀀스.
 *
 * <p>이전 plan 의 동적 PlanSpec 인터프리터 대신 채택. 근거: 9개 의도 중 8개가 완전히 정적인 시퀀스라
 * 런타임 합성이 불필요하다 ({@code AI-pipeline-review-decisions.md} §3).
 *
 * <p>WorkflowService 가 이 맵을 lookup 해 순차 실행한다:
 * <pre>{@code
 * for (StepType step : IntentRouting.ROUTING.get(intent.code())) {
 *     ctx = executors.get(step).execute(ctx);
 * }
 * }</pre>
 *
 * <p>011 LEARNING_PATH, 012 FOLLOWUP, 013 COMPARE 는 베타 후 실제 빈도가 확인되면 추가.
 * 그 전까지는 LLM 분류기가 가장 가까운 기존 코드로 매핑하도록 한다.
 */
public final class IntentRouting {

  public static final Map<IntentCode, List<StepType>> ROUTING =
      Map.ofEntries(
          Map.entry(IntentCode.OUT_OF_DOMAIN, List.of(COMPOSE)),
          Map.entry(IntentCode.COMPOSITION, List.of(COMPOSE)),
          Map.entry(IntentCode.LIGHTING, List.of(COMPOSE)),
          Map.entry(IntentCode.COLOR, List.of(COMPOSE)),
          Map.entry(IntentCode.TECHNIQUE, List.of(COMPOSE)),
          Map.entry(IntentCode.NEW_SEARCH, List.of(EXTRACT_KEYWORDS, SEARCH, COMPOSE)),
          Map.entry(IntentCode.KEEP, List.of(COMPOSE)),
          Map.entry(IntentCode.SKIP, List.of(COMPOSE)),
          Map.entry(IntentCode.GENERATE, List.of(TRANSLATE, GENERATE_IMAGE)),
          Map.entry(IntentCode.SELF_CRITIQUE, List.of(CRITIQUE_UPLOAD, COMPOSE))
          // 011~013 은 베타 후 빈도 확인되면 추가
          );

  private IntentRouting() {}
}
