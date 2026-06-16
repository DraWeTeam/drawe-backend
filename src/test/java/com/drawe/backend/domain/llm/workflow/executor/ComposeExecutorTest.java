package com.drawe.backend.domain.llm.workflow.executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.domain.llm.metrics.LlmMetrics;
import com.drawe.backend.domain.llm.output.OutputIntegrityChecker;
import com.drawe.backend.domain.llm.output.OutputParser;
import com.drawe.backend.domain.llm.service.GrokService;
import com.drawe.backend.domain.llm.service.LlmService;
import com.drawe.backend.global.error.CustomException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ComposeExecutor 단위 테스트(트랙 A ④) — 합성 로직 이관 검증.
 *
 * <p>LlmService 는 익명 fake 로 격리하고, OutputParser·OutputIntegrityChecker 는 실제 객체를 쓴다
 * (③에서 검증된 결정론적 순수 클래스). 검증 포인트: provider 선택, 스키마(draw_guide_response) 강제 전달,
 * references 유무에 따른 SYSTEM turn, ③ 무결성 정정 실연결, offerGenerate 힌트, 멱등성, provider 없음 예외.
 */
class ComposeExecutorTest {

  private final OutputParser parser = new OutputParser(new ObjectMapper());
  private final OutputIntegrityChecker checker = new OutputIntegrityChecker();
  private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
  private final LlmMetrics metrics = new LlmMetrics(registry);

  /** content 를 고정 반환하고, 받은 LlmCallContext 를 캡처하는 fake. */
  private static LlmService fakeLlm(
      LlmProvider provider, String content, AtomicReference<LlmCallContext> captured) {
    return new LlmService() {
      @Override
      public LlmProvider provider() {
        return provider;
      }

      @Override
      public LlmCallResult generate(LlmCallContext context) {
        if (captured != null) {
          captured.set(context);
        }
        return new LlmCallResult(content, "fake-model", 10);
      }
    };
  }

  private static List<ReferenceImage> refs(int count) {
    List<ReferenceImage> list = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      list.add(new ReferenceImage((long) i, i, "u" + i, "p" + i, BigDecimal.ONE, List.of("tag" + i)));
    }
    return list;
  }

  private StepContext ctxWith(
      LlmProvider provider, List<ReferenceImage> references, List<LlmCallContext.Turn> history) {
    return ctxWith(provider, references, history, null);
  }

  private StepContext ctxWith(
      LlmProvider provider,
      List<ReferenceImage> references,
      List<LlmCallContext.Turn> history,
      IntentCode code) {
    StepContext base =
        StepContext.startForCompose(
            1L, 2L, "s1", "벚꽃 그리고 싶어", "벚꽃 그리고 싶어",
            IntentResult.of(code, IntentResult.Tier.RULE),
            null, List.of(), history, null, null, provider);
    return base.withReferences(references);
  }

  private ComposeExecutor executor(LlmService llm) {
    return new ComposeExecutor(List.of(llm), parser, checker, metrics);
  }

  @Nested
  @DisplayName("정상 합성")
  class HappyPath {

    @Test
    @DisplayName("references 있으면 [1]참고 SYSTEM turn 추가 + 스키마 강제 + 본문/citations 채움")
    void withReferences() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1]번처럼 그려보세요\",\"citations\":[1],\"offer_generate\":false}",
              captured);
      StepContext result =
          executor(llm).execute(ctxWith(LlmProvider.GROK, refs(2), List.of()));

      // 스키마 강제 전달
      assertThat(captured.get().responseSchemaName())
          .isEqualTo(GrokService.DRAW_GUIDE_SCHEMA_NAME);
      // references SYSTEM turn 이 붙었다
      assertThat(captured.get().history()).hasSize(1);
      assertThat(captured.get().history().get(0).role()).isEqualTo(MessageRole.SYSTEM);
      assertThat(captured.get().history().get(0).content()).contains("[참고 이미지]");
      // 출력
      assertThat(result.composedOutput().message()).isEqualTo("[1]번처럼 그려보세요");
      assertThat(result.composedOutput().citations()).containsExactly(1);
      assertThat(result.composedAnswer()).isEqualTo("[1]번처럼 그려보세요");
    }

    @Test
    @DisplayName("references 없으면 '참고 없음' SYSTEM turn 추가")
    void withoutReferences() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"자료가 부족해요\",\"citations\":[],\"offer_generate\":true}",
              captured);
      StepContext result =
          executor(llm).execute(ctxWith(LlmProvider.GROK, List.of(), List.of()));

      assertThat(captured.get().history().get(0).content()).contains("참고 이미지가 없습니다");
      assertThat(result.composedOutput().offerGenerate()).isTrue();
    }

    @Test
    @DisplayName("references 없고 intent=FOLLOWUP 이면 'AI 생성 권유' 대신 '후속 질문 안내' turn (베타 오답 차단)")
    void followupWithoutReferences() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"직전 답변을 이어 설명하면…\",\"citations\":[],\"offer_generate\":false}",
              captured);
      executor(llm)
          .execute(ctxWith(LlmProvider.GROK, List.of(), List.of(), IntentCode.FOLLOWUP));

      String guide = captured.get().history().get(0).content();
      // FOLLOWUP 전용 '후속 질문 안내' 가이드가 적용됨 (기본 '참고 이미지 안내'가 아님)
      assertThat(guide).contains("후속 질문 안내");
      assertThat(guide).contains("직전 답변을 이어서");
      assertThat(guide).doesNotContain("참고 이미지가 없습니다");
      // AI 생성 권유 문구는 '금지' 항목으로만 등장 (LLM 에게 그 톤을 쓰지 말라고 지시)
      assertThat(guide).contains("회피·생성 권유");
    }

    @Test
    @DisplayName("references 없고 intent=COMPARE 이면 'AI 생성 권유' 대신 '비교 안내' turn (베타 오답 차단)")
    void compareWithoutReferences() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"1번은 부드럽고 2번은 대비가 강해요…\",\"citations\":[],\"offer_generate\":false}",
              captured);
      executor(llm)
          .execute(ctxWith(LlmProvider.GROK, List.of(), List.of(), IntentCode.COMPARE));

      String guide = captured.get().history().get(0).content();
      // COMPARE 전용 '비교 안내' 가이드가 적용됨 (기본 '참고 이미지 안내'가 아님)
      assertThat(guide).contains("비교 안내");
      assertThat(guide).contains("비교·대조");
      assertThat(guide).doesNotContain("참고 이미지가 없습니다");
      // AI 생성 권유 문구는 '금지' 항목으로만 등장
      assertThat(guide).contains("회피·생성 권유");
    }

    @Test
    @DisplayName("기존 history 뒤에 referenceContext turn 이 append 된다")
    void appendsAfterExistingHistory() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"안녕하세요\",\"citations\":[],\"offer_generate\":false}",
              captured);
      List<LlmCallContext.Turn> persona =
          List.of(new LlmCallContext.Turn(MessageRole.SYSTEM, "persona"));
      executor(llm).execute(ctxWith(LlmProvider.GROK, refs(1), persona));

      assertThat(captured.get().history()).hasSize(2);
      assertThat(captured.get().history().get(0).content()).isEqualTo("persona");
    }
  }

  @Nested
  @DisplayName("KEEP 멀티턴 — previousReferences 재사용")
  class KeepMultiTurn {

    /** references 는 비우고 previousReferences 만 채운 ctx (KEEP 턴 — SearchExecutor 미실행 상태). */
    private StepContext keepCtx(List<ReferenceImage> prev) {
      StepContext base =
          StepContext.startForCompose(
              1L, 2L, "s1", "아까 그거 더 보여줘", "아까 그거 더 보여줘",
              IntentResult.of(null, IntentResult.Tier.RULE),
              null, prev, List.of(), null, null, LlmProvider.GROK);
      // references 는 명시적으로 비운 채(=이번 턴 검색 없음) 둔다.
      return base;
    }

    @Test
    @DisplayName("references 비고 previousReferences 있으면 [참고 이미지] turn 으로 직전 refs 재사용")
    void reusesPreviousReferences() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1]번처럼 이어서 그려보세요\",\"citations\":[1],\"offer_generate\":false}",
              captured);

      StepContext result = executor(llm).execute(keepCtx(refs(2)));

      // "참고 없음" 안내가 아니라 직전 레퍼런스가 [참고 이미지] 컨텍스트로 실렸다.
      assertThat(captured.get().history().get(0).content()).contains("[참고 이미지]");
      assertThat(captured.get().history().get(0).content()).doesNotContain("참고 이미지가 없습니다");
      // 직전 refs 기준 인용이라 [1] 이 환각으로 제거되지 않는다.
      assertThat(result.composedOutput().citations()).containsExactly(1);
      assertThat(result.composedOutput().message()).contains("[1]");
    }

    @Test
    @DisplayName("references 도 previousReferences 도 비면 '참고 없음' 안내로 폴백")
    void noRefsAtAllFallsBackToEmptyNotice() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"자료가 부족해요\",\"citations\":[],\"offer_generate\":true}",
              captured);

      executor(llm).execute(keepCtx(List.of()));

      assertThat(captured.get().history().get(0).content()).contains("참고 이미지가 없습니다");
    }

    @Test
    @DisplayName("이번 턴 references 가 있으면 previousReferences 보다 우선(NEW_SEARCH 우선)")
    void thisTurnReferencesWin() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1] 좋아요\",\"citations\":[1],\"offer_generate\":false}",
              captured);

      // prev=2개, 이번턴=1개 → 이번턴이 이긴다. [1] 만 유효, [2] 는 환각으로 제거되어야 한다.
      StepContext ctx = keepCtx(refs(2)).withReferences(refs(1));
      executor(llm).execute(ctx);

      // 이번 턴 refs(1개) 기준이므로 본문에 [2] 가 있었다면 제거됐을 것. citations 는 [1] 만 통과.
      assertThat(captured.get().history().get(0).content()).contains("[1]");
    }
  }

  @Nested
  @DisplayName("③ 무결성 검사 실연결")
  class Integrity {

    @Test
    @DisplayName("환각 인용([3], refs=2)은 본문·citations 양쪽서 제거")
    void hallucinationRemoved() {
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1]좋고 [3]도 좋아요\",\"citations\":[1,3],\"offer_generate\":false}",
              null);
      StepContext result =
          executor(llm).execute(ctxWith(LlmProvider.GROK, refs(2), List.of()));

      assertThat(result.composedOutput().citations()).containsExactly(1);
      assertThat(result.composedOutput().message()).doesNotContain("[3]");
      assertThat(result.composedOutput().message()).contains("[1]");
    }
  }

  @Nested
  @DisplayName("폴백 / offerGenerate 힌트")
  class FallbackAndHint {

    @Test
    @DisplayName("깨진 JSON 은 원본 노출 없이 안전 템플릿으로 폴백")
    void brokenJsonFallback() {
      LlmService llm = fakeLlm(LlmProvider.GROK, "이건 JSON 이 아님 <원본 노출되면 안 됨>", null);
      StepContext result =
          executor(llm).execute(ctxWith(LlmProvider.GROK, List.of(), List.of()));

      assertThat(result.composedOutput().message())
          .isEqualTo(OutputParser.BROKEN_JSON_FALLBACK_MESSAGE);
      assertThat(result.composedOutput().message()).doesNotContain("원본 노출");
    }

    @Test
    @DisplayName("본문에 생성 안내 표현이 있으면 offerGenerate 강제 true")
    void generateOfferHint() {
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"원하시면 AI 이미지로 만들어드릴까요?\",\"citations\":[],\"offer_generate\":false}",
              null);
      StepContext result =
          executor(llm).execute(ctxWith(LlmProvider.GROK, List.of(), List.of()));

      assertThat(result.composedOutput().offerGenerate()).isTrue();
    }
  }

  @Nested
  @DisplayName("provider 선택 / 멱등성")
  class ProviderAndIdempotency {

    @Test
    @DisplayName("ctx.provider 에 맞는 LlmService 가 선택된다")
    void picksByProvider() {
      AtomicReference<LlmCallContext> grokCaptured = new AtomicReference<>();
      LlmService grok =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"grok\",\"citations\":[],\"offer_generate\":false}",
              grokCaptured);
      LlmService claude =
          fakeLlm(LlmProvider.CLAUDE, "{\"message\":\"claude\",\"citations\":[]}", null);
      ComposeExecutor exec = new ComposeExecutor(List.of(grok, claude), parser, checker, metrics);

      StepContext result = exec.execute(ctxWith(LlmProvider.CLAUDE, List.of(), List.of()));

      assertThat(result.composedOutput().message()).isEqualTo("claude");
      assertThat(grokCaptured.get()).isNull(); // grok 은 호출 안 됨
    }

    @Test
    @DisplayName("provider 에 해당하는 service 가 없으면 CustomException")
    void missingProvider() {
      LlmService grok =
          fakeLlm(LlmProvider.GROK, "{\"message\":\"x\",\"citations\":[]}", null);
      ComposeExecutor exec = new ComposeExecutor(List.of(grok), parser, checker, metrics);

      assertThatThrownBy(() -> exec.execute(ctxWith(LlmProvider.CLAUDE, List.of(), List.of())))
          .isInstanceOf(CustomException.class);
    }

    @Test
    @DisplayName("이미 composedOutput 이 있으면 LLM 호출 없이 통과(멱등)")
    void idempotent() {
      AtomicReference<LlmCallContext> captured = new AtomicReference<>();
      LlmService llm =
          fakeLlm(LlmProvider.GROK, "{\"message\":\"new\",\"citations\":[]}", captured);
      StepContext pre =
          ctxWith(LlmProvider.GROK, List.of(), List.of())
              .withComposedOutput(
                  new com.drawe.backend.domain.llm.output.ComposedOutput("기존", List.of(), false));

      StepContext result = executor(llm).execute(pre);

      assertThat(result.composedOutput().message()).isEqualTo("기존");
      assertThat(captured.get()).isNull(); // 호출 안 됨
    }
  }

  @Nested
  @DisplayName("⑦ DoD 메트릭(§5.2)")
  class Metrics {

    private double counter(String name, String... tags) {
      var c = registry.find(name).tags(tags).counter();
      return c == null ? 0.0 : c.count();
    }

    @Test
    @DisplayName("깨진 JSON → structure_violation{reason=json_broke} 발사")
    void brokenJsonFiresStructureViolation() {
      LlmService llm = fakeLlm(LlmProvider.GROK, "not json", null);
      executor(llm).execute(ctxWith(LlmProvider.GROK, refs(1), List.of()));

      assertThat(counter("drawe.output.structure_violation", "provider", "GROK", "reason", "json_broke"))
          .isEqualTo(1.0);
    }

    @Test
    @DisplayName("정상 JSON 은 structure_violation 발사 안 함")
    void cleanJsonNoViolation() {
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1] 좋아요\",\"citations\":[1],\"offer_generate\":false}",
              null);
      executor(llm).execute(ctxWith(LlmProvider.GROK, refs(1), List.of()));

      assertThat(registry.find("drawe.output.structure_violation").counter()).isNull();
      assertThat(registry.find("drawe.output.hallucinated_citation").counter()).isNull();
    }

    @Test
    @DisplayName("refs 있는데 범위밖 인용 → hallucinated_citation{source=citations_field/body_scan} + citation_removed")
    void rangeViolationFiresBySource() {
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1]좋고 [3]도\",\"citations\":[1,3],\"offer_generate\":false}",
              null);
      executor(llm).execute(ctxWith(LlmProvider.GROK, refs(2), List.of()));

      // [3] 은 citations 슬롯에서 1건, 본문에서 1건 → 각 source 1
      assertThat(counter("drawe.output.hallucinated_citation", "source", "citations_field")).isEqualTo(1.0);
      assertThat(counter("drawe.output.hallucinated_citation", "source", "body_scan")).isEqualTo(1.0);
      assertThat(counter("drawe.output.hallucinated_citation", "source", "no_refs")).isZero();
      assertThat(counter("drawe.output.citation_removed")).isEqualTo(2.0);
    }

    @Test
    @DisplayName("refs 0인데 인용 → hallucinated_citation{source=no_refs} 로 합산")
    void noRefsViolationTaggedNoRefs() {
      LlmService llm =
          fakeLlm(
              LlmProvider.GROK,
              "{\"message\":\"[1] 보세요\",\"citations\":[1],\"offer_generate\":false}",
              null);
      executor(llm).execute(ctxWith(LlmProvider.GROK, List.of(), List.of()));

      assertThat(counter("drawe.output.hallucinated_citation", "source", "no_refs")).isEqualTo(2.0);
      assertThat(counter("drawe.output.hallucinated_citation", "source", "citations_field")).isZero();
      assertThat(counter("drawe.output.citation_removed")).isEqualTo(2.0);
    }
  }
}
