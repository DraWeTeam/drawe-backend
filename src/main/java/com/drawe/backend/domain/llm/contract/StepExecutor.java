package com.drawe.backend.domain.llm.contract;

/**
 * 파이프라인의 한 step 을 실행하는 단위. A·B 가 각자 자기 {@link StepType} 에 대해 구현체를 만든다.
 *
 * <p>스프링이 모든 {@code StepExecutor} 빈을 수집해 {@code Map<StepType, StepExecutor>} 로 자동 주입한다
 * (WorkflowService 에서). 따라서 새 step 을 추가하려면 {@link StepType} 에 enum 추가 + Executor 빈 등록만 하면 된다.
 *
 * <p>반환값은 갱신된 {@link StepContext} 직접. 별도 {@code StepResult} 래퍼를 두지 않는다 —
 * 메트릭(latency, tier, intent code 태그) 은 WorkflowService 가 {@code execute} 호출을
 * Micrometer Timer 로 감싸서 측정한다.
 *
 * <p>예시 (B 의 ExtractKeywordsExecutor):
 * <pre>{@code
 * @Component
 * @RequiredArgsConstructor
 * public class ExtractKeywordsExecutor implements StepExecutor {
 *     private final KomoranKeywordExtractor extractor;
 *
 *     @Override public StepType type() { return StepType.EXTRACT_KEYWORDS; }
 *
 *     @Override public StepContext execute(StepContext ctx) {
 *         List<String> kw = extractor.extract(ctx.cleanedMessage());
 *         return ctx.withKeywords(kw);
 *     }
 * }
 * }</pre>
 */
public interface StepExecutor {

  /** 이 Executor 가 처리하는 step 종류. 스프링 빈 맵의 키. */
  StepType type();

  /**
   * step 실행. 갱신된 컨텍스트를 반환한다.
   *
   * <p>예외는 던지지 말고 폴백 처리한 컨텍스트를 반환할 것 — 한 step 실패가 전체 파이프라인을 깨지 않도록.
   * 부득이한 경우만 {@link RuntimeException} 을 던지면 WorkflowService 가 안전 폴백 응답으로 처리.
   */
  StepContext execute(StepContext ctx);
}
