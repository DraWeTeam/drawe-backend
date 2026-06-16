package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * GENERATE_IMAGE 단계 실행기 — Bria API 로 이미지를 생성한다 (A 소유).
 *
 * <p><b>골격(트랙 A ③)</b>: 실제 생성은 {@code ImageGenerationService.generate(User, String, Project)} 로,
 * {@code User}/{@code Project} 엔티티를 요구한다(TranslateExecutor 와 동일 제약). 또 현재 {@code
 * ChatLlmService.handleGenerateNow()} 가 이 흐름(생성 + 세션 메시지 기록 + 서명 URL)을 통째로 처리하고 있어, 이관 시
 * 그 책임 분할도 함께 정해야 한다.
 *
 * <p>TODO(트랙 A ② 이후): ImageGenerationService 위임 → {@code ctx.withGeneratedImage(GenerateImageResponse)}.
 * TRANSLATE 가 만든 영문 프롬프트를 입력으로 받음.
 */
@Slf4j
@Component
public class GenerateImageExecutor implements StepExecutor {

  @Override
  public StepType type() {
    return StepType.GENERATE_IMAGE;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    if (ctx.generatedImage() != null) {
      return ctx;
    }
    // 골격: ImageGenerationService 위임 미이관. 컨텍스트 통과.
    // 이 골격이 실제 실행됐다 = 미구현 의도(GENERATE)가 live 워크플로에 도달했다는 신호(R1).
    // 정상 경로면 WorkflowComposeProperties 부팅 검증이 막으므로, WARN 이 찍히면 라우팅/게이트 회귀다.
    log.warn("GENERATE_IMAGE 골격 실행됨 — 생성 미이관(미구현 의도가 live 도달). composedOutput=null→500 위험 신호.");
    return ctx;
  }
}
