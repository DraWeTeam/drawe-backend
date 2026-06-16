package com.drawe.backend.domain.llm.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.domain.search.service.SearchService;
import com.drawe.backend.global.client.FastApiClient;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CritiqueUploadExecutor 단위 테스트 (S3' 트랙 A ① a1 + ② a2).
 *
 * <p>검증: ①이미지 없으면 통과(임베딩 호출 안 함), ②이미지 있고 유사검색 성공 → refs 채움 + [N] 인용 허용 가이드,
 * ③저점수 → refs 비움 + 인용 금지 가이드(폴백), ④임베딩/검색 예외 → 폴백, ⑤기존 history 보존.
 */
class CritiqueUploadExecutorTest {

  private final FastApiClient fastApiClient = mock(FastApiClient.class);
  private final SearchService searchService = mock(SearchService.class);
  private final CritiqueUploadExecutor executor =
      new CritiqueUploadExecutor(fastApiClient, searchService);

  private static StepContext ctxWith(byte[] imageBytes, List<LlmCallContext.Turn> history) {
    IntentResult intent =
        new IntentResult(IntentCode.SELF_CRITIQUE, List.of(), imageBytes != null, IntentResult.Tier.RULE);
    return StepContext.startForCompose(
        1L, 2L, "session-1", "이거 어때?", "이거 어때?", intent, "/images/9",
        List.of(), history, imageBytes, "image/png", LlmProvider.GROK);
  }

  private static ImageResult result(long id, float score) {
    return new ImageResult(
        id, "src" + id, "http://img/" + id, "user" + id, "name" + id, score,
        "수채화", "인물", "차분", List.of(), List.of(), List.of(), "UNSPLASH");
  }

  @Test
  @DisplayName("type() 은 CRITIQUE_UPLOAD")
  void type() {
    assertThat(executor.type()).isEqualTo(StepType.CRITIQUE_UPLOAD);
  }

  @Test
  @DisplayName("이미지 없으면 통과 — 임베딩/검색 호출 안 함")
  void passesThroughWhenNoImage() {
    LlmCallContext.Turn persona = new LlmCallContext.Turn(MessageRole.SYSTEM, "[페르소나]");
    StepContext result = executor.execute(ctxWith(null, List.of(persona)));

    assertThat(result.history()).containsExactly(persona);
    assertThat(result.references()).isEmpty();
    verifyNoInteractions(fastApiClient, searchService);
  }

  @Test
  @DisplayName("유사검색 성공(고점수) → refs 채움 + [N] 인용 허용 가이드")
  void attachesSimilarReferences() {
    when(fastApiClient.embedImage(any(), eq("image/png"))).thenReturn(List.of(0.1f, 0.2f));
    when(searchService.searchByVector(any(), anyInt()))
        .thenReturn(new SearchResponse(List.of(result(1, 0.5f), result(2, 0.4f)), 2, ""));

    StepContext result = executor.execute(ctxWith(new byte[] {1, 2, 3}, List.of()));

    assertThat(result.references()).hasSize(2);
    assertThat(result.references().get(0).index()).isEqualTo(1); // 1-based
    assertThat(result.references().get(1).index()).isEqualTo(2);
    assertThat(result.history()).hasSize(1);
    assertThat(result.history().get(0).content()).isEqualTo(CritiqueUploadExecutor.CRITIQUE_GUIDE_WITH_REFS);
  }

  @Test
  @DisplayName("저점수 → 점수가드 차단 → refs 비움 + 인용 금지 가이드(텍스트 비평 폴백)")
  void lowScoreFallsBackToTextCritique() {
    when(fastApiClient.embedImage(any(), any())).thenReturn(List.of(0.1f));
    when(searchService.searchByVector(any(), anyInt()))
        .thenReturn(new SearchResponse(List.of(result(1, 0.15f), result(2, 0.1f)), 2, ""));

    StepContext result = executor.execute(ctxWith(new byte[] {1}, List.of()));

    assertThat(result.references()).isEmpty();
    assertThat(result.history().get(0).content()).isEqualTo(CritiqueUploadExecutor.CRITIQUE_GUIDE_NO_REFS);
  }

  @Test
  @DisplayName("검색 결과 0건 → 인용 금지 가이드(폴백)")
  void emptyResultsFallsBack() {
    when(fastApiClient.embedImage(any(), any())).thenReturn(List.of(0.1f));
    when(searchService.searchByVector(any(), anyInt())).thenReturn(new SearchResponse(List.of(), 0, ""));

    StepContext result = executor.execute(ctxWith(new byte[] {1}, List.of()));

    assertThat(result.references()).isEmpty();
    assertThat(result.history().get(0).content()).isEqualTo(CritiqueUploadExecutor.CRITIQUE_GUIDE_NO_REFS);
  }

  @Test
  @DisplayName("임베딩/검색 예외 → 삼키고 텍스트 비평 폴백 (비평 자체는 진행)")
  void searchExceptionFallsBack() {
    when(fastApiClient.embedImage(any(), any())).thenThrow(new RuntimeException("embed down"));

    StepContext result = executor.execute(ctxWith(new byte[] {1}, List.of()));

    assertThat(result.references()).isEmpty();
    assertThat(result.history().get(0).content()).isEqualTo(CritiqueUploadExecutor.CRITIQUE_GUIDE_NO_REFS);
    verify(searchService, never()).searchByVector(any(), anyInt());
  }
}
