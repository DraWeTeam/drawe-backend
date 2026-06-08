package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * TRANSLATE 단계 실행기 — 한국어 이미지 생성 요청을 Bria 용 영문 프롬프트로 변환한다 (A 소유).
 *
 * <p><b>골격(트랙 A ③)</b>: 실제 변환은 {@code PromptTranslator.translate(User, String, Project)} 로,
 * {@code User}/{@code Project} 엔티티를 요구한다. {@link StepContext} 는 userId/projectId(Long)만 들고 있어
 * 엔티티 조회 경로가 필요하다. 이 경로(Repository 주입 vs StepContext 확장)는 ② 이후 합의 대상이라 골격으로 둔다.
 *
 * <p>TODO(트랙 A ② 이후): PromptTranslator 위임 + 변환된 영문 프롬프트를 후속 GENERATE_IMAGE 가 쓰도록 StepContext
 * 슬롯에 싣는다(현재 전용 슬롯 없음 — ② 에서 결정).
 */
@Slf4j
@Component
public class TranslateExecutor implements StepExecutor {

  @Override
  public StepType type() {
    return StepType.TRANSLATE;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    // 골격: PromptTranslator 위임 미이관. 컨텍스트 통과.
    log.debug("TRANSLATE 골격 — 변환 미이관, 컨텍스트 통과");
    return ctx;
  }
}
