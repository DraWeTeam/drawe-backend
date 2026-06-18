package com.drawe.backend.domain.llm.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WorkflowService} 단위 테스트. mock {@link StepExecutor} 로 ROUTING 순회·누적·폴백·빈충돌을 검증한다.
 * 설계: 트랙 A ③. chat() 실연결 전 단계라 순수 단위로만.
 */
class WorkflowServiceTest {

  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

  /** 실행 순서를 기록하는 mock executor. 지정 슬롯(keywords)에 흔적을 남겨 누적을 검증한다. */
  private StepExecutor recording(StepType type, List<StepType> log) {
    return new StepExecutor() {
      @Override
      public StepType type() {
        return type;
      }

      @Override
      public StepContext execute(StepContext ctx) {
        log.add(type);
        // 누적 검증: keywords 슬롯에 step 이름을 append
        List<String> kw = new ArrayList<>(ctx.keywords());
        kw.add(type.name());
        return ctx.withKeywords(kw);
      }
    };
  }

  private StepExecutor throwing(StepType type) {
    return new StepExecutor() {
      @Override
      public StepType type() {
        return type;
      }

      @Override
      public StepContext execute(StepContext ctx) {
        throw new RuntimeException("boom");
      }
    };
  }

  private StepContext start() {
    return StepContext.start(1L, 2L, "s", "raw", "clean", IntentResult.of(IntentCode.NEW_SEARCH, IntentResult.Tier.RULE), null, List.of());
  }

  @Test
  @DisplayName("NEW_SEARCH ROUTING(EXTRACT_KEYWORDS→SEARCH→COMPOSE) 을 순서대로 실행하고 누적한다")
  void runsRoutingInOrder() {
    List<StepType> order = new ArrayList<>();
    WorkflowService wf =
        new WorkflowService(
            List.of(
                recording(StepType.EXTRACT_KEYWORDS, order),
                recording(StepType.SEARCH, order),
                recording(StepType.COMPOSE, order)),
            registry);

    StepContext result = wf.run(IntentResult.of(IntentCode.NEW_SEARCH, IntentResult.Tier.RULE), start());

    // ROUTING.NEW_SEARCH = [EXTRACT_KEYWORDS, SEARCH, COMPOSE]
    assertThat(order)
        .containsExactly(StepType.EXTRACT_KEYWORDS, StepType.SEARCH, StepType.COMPOSE);
    // 누적: 각 step 이 keywords 에 흔적 남김
    assertThat(result.keywords())
        .containsExactly("EXTRACT_KEYWORDS", "SEARCH", "COMPOSE");
  }

  @Test
  @DisplayName("Executor 미등록 step 은 건너뛰고 나머지를 실행한다 (파이프라인 안 깨짐)")
  void skipsMissingExecutor() {
    List<StepType> order = new ArrayList<>();
    // COMPOSE Executor 를 일부러 빼고 등록
    WorkflowService wf =
        new WorkflowService(
            List.of(
                recording(StepType.EXTRACT_KEYWORDS, order),
                recording(StepType.SEARCH, order)),
            registry);

    StepContext result = wf.run(IntentResult.of(IntentCode.NEW_SEARCH, IntentResult.Tier.RULE), start());

    assertThat(order).containsExactly(StepType.EXTRACT_KEYWORDS, StepType.SEARCH);
    assertThat(result.keywords()).containsExactly("EXTRACT_KEYWORDS", "SEARCH");
  }

  @Test
  @DisplayName("step 예외 시 누적 컨텍스트를 보존하고 다음 step 을 계속한다")
  void preservesContextOnStepFailure() {
    List<StepType> order = new ArrayList<>();
    WorkflowService wf =
        new WorkflowService(
            List.of(
                recording(StepType.EXTRACT_KEYWORDS, order),
                throwing(StepType.SEARCH), // 가운데 step 실패
                recording(StepType.COMPOSE, order)),
            registry);

    StepContext result = wf.run(IntentResult.of(IntentCode.NEW_SEARCH, IntentResult.Tier.RULE), start());

    // SEARCH 는 던졌지만 EXTRACT_KEYWORDS·COMPOSE 는 실행됨
    assertThat(order).containsExactly(StepType.EXTRACT_KEYWORDS, StepType.COMPOSE);
    // SEARCH 실패로 그 흔적은 없지만 앞 step 결과는 보존
    assertThat(result.keywords()).containsExactly("EXTRACT_KEYWORDS", "COMPOSE");
  }

  @Test
  @DisplayName("같은 StepType 을 두 Executor 가 주장하면 생성자에서 막는다")
  void rejectsDuplicateStepType() {
    List<StepType> order = new ArrayList<>();
    assertThatThrownBy(
            () ->
                new WorkflowService(
                    List.of(
                        recording(StepType.SEARCH, order), recording(StepType.SEARCH, order)),
                    registry))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("SEARCH");
  }

  @Test
  @DisplayName("GENERATE ROUTING(TRANSLATE→GENERATE_IMAGE) — 검색 step 없이 생성 경로")
  void generateRoute() {
    List<StepType> order = new ArrayList<>();
    WorkflowService wf =
        new WorkflowService(
            List.of(
                recording(StepType.TRANSLATE, order),
                recording(StepType.GENERATE_IMAGE, order)),
            registry);

    wf.run(IntentResult.of(IntentCode.GENERATE, IntentResult.Tier.RULE), start());

    assertThat(order).containsExactly(StepType.TRANSLATE, StepType.GENERATE_IMAGE);
  }

  @Test
  @DisplayName("COMPARE ROUTING(COMPOSE) — 검색·생성 없이 COMPOSE 만 (013, FOLLOWUP 과 동일 종착)")
  void compareRoute() {
    List<StepType> order = new ArrayList<>();
    WorkflowService wf =
        new WorkflowService(List.of(recording(StepType.COMPOSE, order)), registry);

    wf.run(IntentResult.of(IntentCode.COMPARE, IntentResult.Tier.LLM_LIGHT), start());

    assertThat(order).containsExactly(StepType.COMPOSE);
  }

  @Test
  @DisplayName("step Timer 가 메트릭에 기록된다")
  void recordsStepTimer() {
    List<StepType> order = new ArrayList<>();
    WorkflowService wf =
        new WorkflowService(List.of(recording(StepType.COMPOSE, order)), registry);

    wf.run(IntentResult.of(IntentCode.COMPOSITION, IntentResult.Tier.RULE), start());

    assertThat(
            registry
                .find("drawe.workflow.step")
                .tag("step", "COMPOSE")
                .tag("outcome", "success")
                .timer())
        .isNotNull();
  }
}
