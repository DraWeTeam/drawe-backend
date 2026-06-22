package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CRITIQUE_UPLOAD 단계 실행기 — 사용자가 업로드한 본인 작업물을 비평한다 (010 SELF_CRITIQUE, A+B 공동).
 *
 * <p><b>골격(트랙 A ③)</b>: 010 SELF_CRITIQUE 는 멀티모달 입력 경로(이미지 임베딩=B, compose=A)로 ADR §5/§9 의 S3' 도입
 * 항목이다. 현재 미구현 의도이며 Executor 자리만 잡아둔다. {@link StepContext#uploadedImageUrl} 를 입력으로 받게 된다.
 *
 * <p>TODO(S3'): FastApiClient.embedImage 로 업로드 이미지 임베딩 → 유사 레퍼런스/평가 합성. 현재는 통과만.
 */
@Slf4j
@Component
public class CritiqueUploadExecutor implements StepExecutor {

  @Override
  public StepType type() {
    return StepType.CRITIQUE_UPLOAD;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    // 골격: 멀티모달 비평 미구현(S3'). 컨텍스트 통과.
    log.debug("CRITIQUE_UPLOAD 골격 — 미구현, 컨텍스트 통과");
    return ctx;
  }
}
