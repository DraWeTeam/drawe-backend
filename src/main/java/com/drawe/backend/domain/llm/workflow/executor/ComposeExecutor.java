package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * COMPOSE 단계 실행기 — 페르소나 + 레퍼런스 컨텍스트로 최종 가이드 응답을 LLM 으로 생성한다 (A 소유).
 *
 * <p><b>골격(트랙 A ③)</b>: 실제 합성은 {@code ChatLlmService.chat()} 의 LLM 호출 로직이 담당하며, 그 로직은
 * 전체 history·persona·provider 선택·이미지 입력 등 {@link StepContext} 가 아직 담지 않는 정보를 필요로 한다. 이
 * Executor 로 옮기려면 ② 경량 분류기가 IntentResult 를 만들고 StepContext 가 그 정보를 싣도록 확장된 뒤라야 한다.
 * 그전까지는 빈 슬롯을 보존만 하고 통과시킨다(파이프라인을 깨지 않음).
 *
 * <p>TODO(트랙 A ② 이후): ChatLlmService 의 LLM 합성 로직을 여기로 이동, {@code ctx.withComposedAnswer(...)}
 * 로 채운다. history/persona 접근 경로는 ② 에서 StepContext 확장으로 합의.
 */
@Slf4j
@Component
public class ComposeExecutor implements StepExecutor {

  @Override
  public StepType type() {
    return StepType.COMPOSE;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    if (ctx.composedAnswer() != null) {
      return ctx;
    }
    // 골격: 아직 합성 로직 미이관. 누적 컨텍스트 그대로 통과.
    log.debug("COMPOSE 골격 — 합성 미이관, 컨텍스트 통과: code={}", ctx.intent() != null ? ctx.intent().code() : null);
    return ctx;
  }
}
