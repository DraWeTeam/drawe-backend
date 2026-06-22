package com.drawe.backend.domain.llm.contract;

/**
 * 파이프라인 step 실행 인터페이스.
 *
 * <p>Spring {@code Map<StepType, StepExecutor>} 자동 주입 방식. {@code WorkflowService} 가 {@link
 * IntentRouting#ROUTING} 으로 step 시퀀스 조회 후 각 step 의 {@link #execute(StepContext)} 를 순차 호출.
 *
 * <h2>예외 정책 (#5 답변 박제)</h2>
 *
 * <p>한 step 실패가 전체 파이프라인을 깨지 않도록:
 *
 * <ul>
 *   <li>일반 오류: <strong>예외 던지지 말고</strong> 폴백 처리한 ctx 반환 (예: SEARCH 실패 → 빈 references)
 *   <li>치명적 오류만: {@code RuntimeException} 던짐 (DI 문제, NPE 등)
 * </ul>
 *
 * <h2>메트릭</h2>
 *
 * <p>각 executor 내부에서 measure 하지 말고, {@code WorkflowService} 가 {@code execute()} 호출을 Micrometer
 * Timer 로 감싸서 측정. 태그: {@code step}, {@code intent}, {@code tier}.
 */
public interface StepExecutor {

  /** 이 executor 가 담당하는 step 종류. Spring 의 Map<StepType, StepExecutor> 주입 키로 사용. */
  StepType type();

  /**
   * step 실행.
   *
   * @param ctx 입력 ctx
   * @return 갱신된 ctx (입력 ctx 와 별개 인스턴스 — {@code @With} 가 자동 생성한 wither 사용)
   */
  StepContext execute(StepContext ctx);
}
