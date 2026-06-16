package com.drawe.backend.domain.llm.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CritiqueUploadExecutor 단위 테스트 (S3' 트랙 A ①, a1).
 *
 * <p>검증 포인트: ①업로드 이미지가 있으면 비평 가이드 SYSTEM turn 을 history 끝에 추가, ②이미지가 없으면 통과(무주입),
 * ③기존 history 보존(비파괴 누적), ④type() 정합.
 */
class CritiqueUploadExecutorTest {

  private final CritiqueUploadExecutor executor = new CritiqueUploadExecutor();

  private static StepContext ctxWith(
      byte[] imageBytes, String mime, List<LlmCallContext.Turn> history) {
    IntentResult intent =
        new IntentResult(IntentCode.SELF_CRITIQUE, List.of(), imageBytes != null, IntentResult.Tier.RULE);
    return StepContext.startForCompose(
        1L,
        2L,
        "session-1",
        "이거 어때?",
        "이거 어때?",
        intent,
        "/images/9",
        List.of(),
        history,
        imageBytes,
        mime,
        LlmProvider.GROK);
  }

  @Test
  @DisplayName("type() 은 CRITIQUE_UPLOAD")
  void type() {
    assertThat(executor.type()).isEqualTo(StepType.CRITIQUE_UPLOAD);
  }

  @Test
  @DisplayName("업로드 이미지가 있으면 비평 가이드 SYSTEM turn 을 history 끝에 추가한다")
  void injectsCritiqueGuideWhenImagePresent() {
    LlmCallContext.Turn persona = new LlmCallContext.Turn(MessageRole.SYSTEM, "[페르소나]");
    StepContext ctx = ctxWith(new byte[] {1, 2, 3}, "image/png", List.of(persona));

    StepContext result = executor.execute(ctx);

    assertThat(result.history()).hasSize(2);
    assertThat(result.history().get(0)).isEqualTo(persona); // 기존 turn 보존
    LlmCallContext.Turn injected = result.history().get(1);
    assertThat(injected.role()).isEqualTo(MessageRole.SYSTEM);
    assertThat(injected.content()).isEqualTo(CritiqueUploadExecutor.CRITIQUE_GUIDE);
  }

  @Test
  @DisplayName("업로드 이미지가 없으면 아무것도 주입하지 않고 통과한다")
  void passesThroughWhenNoImage() {
    LlmCallContext.Turn persona = new LlmCallContext.Turn(MessageRole.SYSTEM, "[페르소나]");
    StepContext ctx = ctxWith(null, null, List.of(persona));

    StepContext result = executor.execute(ctx);

    assertThat(result.history()).containsExactly(persona);
  }

  @Test
  @DisplayName("빈 바이트 배열도 이미지 없음으로 보고 통과한다")
  void passesThroughWhenEmptyImage() {
    StepContext ctx = ctxWith(new byte[0], "image/png", List.of());

    StepContext result = executor.execute(ctx);

    assertThat(result.history()).isEmpty();
  }
}
