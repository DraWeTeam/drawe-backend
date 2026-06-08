package com.drawe.backend.domain.llm.workflow;

import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.contract.IntentRouting;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * AI 파이프라인 오케스트레이터 — {@link IntentRouting#ROUTING} 의 step 시퀀스를 순서대로 실행한다.
 *
 * <p>정적 전략 맵 패턴(ADR §3): 동적 PlanSpec 인터프리터 대신 {@code IntentCode → List<StepType>} 정적 맵을
 * lookup 해 실행. 각 {@link StepExecutor} 는 스프링이 타입별로 자동 수집해 {@code Map<StepType, StepExecutor>} 로
 * 주입한다 — 새 step 추가 = enum + Executor 빈 등록만.
 *
 * <p><b>현재(트랙 A ③) 상태</b>: WorkflowService 와 Executor 골격은 준비됐으나 {@code ChatLlmService.chat()} 에
 * 아직 연결하지 않는다. 실연결은 ② 경량 LLM 분류기(IntentResult 생성)와 모든 step 의 Executor 가 갖춰진 뒤. 그전까지는
 * 단위 테스트로만 검증한다.
 *
 * <p>step 실패 정책: 한 step 이 던진 예외가 전체 파이프라인을 깨지 않도록 잡아서 로그하고 **현재까지 누적된 컨텍스트를 그대로
 * 반환**한다(부분 성공). 이미 채워진 슬롯(예: keywords 까지 됐고 search 실패)은 보존된다.
 */
@Slf4j
@Service
public class WorkflowService {

  private final Map<StepType, StepExecutor> executors;
  private final MeterRegistry meterRegistry;

  /**
   * @param executorList 스프링이 수집한 모든 {@link StepExecutor} 빈. {@code type()} 키로 맵을 만든다. 같은
   *     StepType 을 두 빈이 주장하면 빈 등록 단계에서 막아야 하므로 명시적으로 검사한다.
   */
  public WorkflowService(List<StepExecutor> executorList, MeterRegistry meterRegistry) {
    Map<StepType, StepExecutor> map = new EnumMap<>(StepType.class);
    for (StepExecutor e : executorList) {
      StepExecutor prev = map.put(e.type(), e);
      if (prev != null) {
        throw new IllegalStateException(
            "StepType " + e.type() + " 에 Executor 가 둘 이상: " + prev.getClass() + ", " + e.getClass());
      }
    }
    this.executors = map;
    this.meterRegistry = meterRegistry;
  }

  /**
   * intent 에 매핑된 step 시퀀스를 순서대로 실행해 최종 컨텍스트를 반환한다.
   *
   * @param intent 분류 결과 (code 로 ROUTING lookup, tier 는 메트릭 태그)
   * @param initial 시작 컨텍스트 ({@link StepContext#start})
   * @return 모든 step 을 거쳐 누적된 컨텍스트
   */
  public StepContext run(IntentResult intent, StepContext initial) {
    List<StepType> steps = IntentRouting.ROUTING.get(intent.code());
    if (steps == null || steps.isEmpty()) {
      log.warn("ROUTING 에 매핑 없음 — 빈 시퀀스로 처리: code={}", intent.code());
      return initial;
    }

    StepContext ctx = initial;
    for (StepType step : steps) {
      StepExecutor executor = executors.get(step);
      if (executor == null) {
        // ROUTING 이 참조하는 step 의 Executor 빈이 아직 없음(미구현). 파이프라인을 깨지 않고 건너뛴다.
        log.warn("StepExecutor 미등록 — step 건너뜀: step={}, code={}", step, intent.code());
        continue;
      }
      ctx = runStep(executor, step, intent, ctx);
    }
    return ctx;
  }

  /** 한 step 을 Micrometer Timer 로 감싸 실행. 예외는 잡아 누적 컨텍스트 보존. */
  private StepContext runStep(
      StepExecutor executor, StepType step, IntentResult intent, StepContext ctx) {
    Timer.Sample sample = Timer.start(meterRegistry);
    String outcome = "success";
    try {
      return executor.execute(ctx);
    } catch (RuntimeException e) {
      outcome = "error";
      log.error(
          "step 실행 실패 — 누적 컨텍스트 보존하고 계속: step={}, code={}, error_class={}",
          step,
          intent.code(),
          e.getClass().getSimpleName());
      return ctx; // 부분 성공: 이전 step 까지 채운 컨텍스트 유지
    } finally {
      sample.stop(
          Timer.builder("drawe.workflow.step")
              .tag("step", step.name())
              .tag("code", intent.code().code())
              .tag("tier", intent.tier().name())
              .tag("outcome", outcome)
              .register(meterRegistry));
    }
  }
}
