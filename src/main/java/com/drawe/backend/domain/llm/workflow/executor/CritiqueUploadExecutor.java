package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CRITIQUE_UPLOAD 단계 실행기 — 사용자가 업로드한 본인 작업물을 비평한다 (010 SELF_CRITIQUE).
 *
 * <p><b>1차(S3' 트랙 A, a1 — LLM 비전만)</b>: 실제 비전 호출은 후속 COMPOSE 가 이미 한다
 * ({@code ComposeExecutor} 가 {@code ctx.uploadedImageBytes()} 를 {@code LlmCallContext} 에 실어 멀티모달로
 * 보냄). 따라서 이 step 이 하는 일은 <b>"비평 모드" 가이드를 SYSTEM turn 으로 history 에 주입</b>하는 것뿐이다 —
 * COMPOSE 가 그 turn 을 읽어 비평 톤으로 합성한다. 설계: {@code docs/decisions/S3A-self-critique-design.md} §2.1.
 *
 * <p>업로드 이미지가 없으면(방어) 아무것도 하지 않고 통과한다. 비평할 대상이 없는데 비평 가이드를 주입하면
 * LLM 이 못 본 이미지를 비평하는 척할 수 있어서다.
 *
 * <p><b>범위 밖(2차, a2)</b>: CLIP 임베딩({@code FastApiClient.embedImage}) → 유사 레퍼런스 검색·첨부는
 * 후속 작업이다(설계 §7). 그때 이 Executor 가 {@code ctx.references} 를 채우게 된다.
 */
@Slf4j
@Component
public class CritiqueUploadExecutor implements StepExecutor {

  /**
   * 비평 모드 가이드. references 가 없는 비평이라 [N] 인용은 금지된다(COMPOSE 의 "참고 없음" 안내와 정합 —
   * 결정론적 무결성 체커가 범위밖 인용을 제거하므로 환각 인용은 구조적으로 차단된다). 페르소나 [자율성 경계]의
   * '평가 금지'는 사용자가 평가를 명시 요청한 이 의도에서만 해제한다.
   */
  static final String CRITIQUE_GUIDE =
      "[작업물 비평 안내]\n"
          + "사용자가 본인이 그린 작업물을 직접 올리고 평가를 요청했습니다. 첨부된 이미지를 보고 비평해주세요.\n"
          + "\n"
          + "응답 가이드:\n"
          + "- 구도 / 명암 / 색 / 형태 관점에서 구체적이고 건설적인 피드백을 주세요.\n"
          + "- 잘된 점을 먼저 1가지 짚고, 개선하면 좋을 점을 1~2가지 제안하세요.\n"
          + "- 사용자가 평가를 직접 요청했으므로 이 답변에서는 솔직한 피드백이 허용됩니다(평소의 평가 자제 해제).\n"
          + "- 단정·강요는 하지 말고, \"이렇게 해보면 어떨까요?\" 같은 권유 톤을 유지하세요.\n"
          + "\n"
          + "금지:\n"
          + "- [1], [2] 같은 인용 표현 (지금은 검색된 참고 이미지가 없음).\n"
          + "- 사용자를 깎아내리거나 \"틀렸어요\" 같은 단정적 부정.\n"
          + "- 못 본 이미지를 본 척하거나, 만들지 않은 이미지를 만든 척하는 표현.";

  @Override
  public StepType type() {
    return StepType.CRITIQUE_UPLOAD;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    byte[] image = ctx.uploadedImageBytes();
    if (image == null || image.length == 0) {
      // 비평 대상 이미지 없음 — 가이드 주입 안 함(못 본 이미지 비평 방지). 컨텍스트 통과.
      log.warn("CRITIQUE_UPLOAD: 업로드 이미지 없음 — 비평 가이드 미주입, 통과");
      return ctx;
    }

    List<LlmCallContext.Turn> history = new ArrayList<>(ctx.history());
    history.add(new LlmCallContext.Turn(MessageRole.SYSTEM, CRITIQUE_GUIDE));
    log.debug("CRITIQUE_UPLOAD: 비평 가이드 SYSTEM turn 주입 (bytes={})", image.length);
    return ctx.withHistory(history);
  }
}
