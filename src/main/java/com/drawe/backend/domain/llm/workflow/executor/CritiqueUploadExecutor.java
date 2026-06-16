package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CRITIQUE_UPLOAD 단계 실행기 — 사용자가 업로드한 본인 작업물을 비평한다 (010 SELF_CRITIQUE, A+B 공동).
 *
 * <p><b>골격(트랙 A ③)</b>: 010 SELF_CRITIQUE 는 멀티모달 입력 경로(이미지 임베딩=B, compose=A)로 ADR §5/§9 의
 * S3' 도입 항목이다. 현재 미구현 의도이며 Executor 자리만 잡아둔다. {@link StepContext#uploadedImageUrl} 를 입력으로
 * 받게 된다.
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
    // 뒤에 COMPOSE 가 있어 500 은 안 나지만(비평 컨텍스트만 누락된 채 응답), 이 골격이 실행됐다 =
    // SELF_CRITIQUE 가 live 도달했다는 신호라 WARN 으로 남긴다(조용한 품질 저하 방지).
    log.warn("CRITIQUE_UPLOAD 골격 실행됨 — 멀티모달 비평 미구현. 비평 컨텍스트 없이 COMPOSE 진행됨.");
    return ctx;
  }
}
