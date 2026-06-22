package com.drawe.backend.domain.llm.classifier;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.dto.ExtractionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IntentResultAdapter} 단위 테스트 — 4 Action → IntentCode 매핑 + tier 판정 + 슬롯 전달. 설계: {@code
 * docs/decisions/S1A-intent-classifier-design.md}.
 */
class IntentResultAdapterTest {

  private final IntentResultAdapter adapter = new IntentResultAdapter();

  @Test
  @DisplayName("6 Action → IntentCode 매핑")
  void actionToCode() {
    assertThat(adapter.adapt(ExtractionResult.newSearch("k"), true, List.of(), false).code())
        .isEqualTo(IntentCode.NEW_SEARCH);
    assertThat(adapter.adapt(ExtractionResult.keep(), false, List.of(), false).code())
        .isEqualTo(IntentCode.KEEP);
    assertThat(adapter.adapt(ExtractionResult.skip(), true, List.of(), false).code())
        .isEqualTo(IntentCode.SKIP);
    assertThat(adapter.adapt(ExtractionResult.generateNow("p"), true, List.of(), false).code())
        .isEqualTo(IntentCode.GENERATE);
    assertThat(adapter.adapt(ExtractionResult.followup(), false, List.of(), false).code())
        .isEqualTo(IntentCode.FOLLOWUP);
    assertThat(adapter.adapt(ExtractionResult.compare(), false, List.of(), false).code())
        .isEqualTo(IntentCode.COMPARE);
  }

  @Test
  @DisplayName("FOLLOWUP → 012, Grok 결정이면 tier=LLM_LIGHT (012 는 history 의존이라 룰로 안 잡힘)")
  void followupToCode() {
    IntentResult r = adapter.adapt(ExtractionResult.followup(), false, List.of(), false);
    assertThat(r.code()).isEqualTo(IntentCode.FOLLOWUP);
    assertThat(r.tier()).isEqualTo(IntentResult.Tier.LLM_LIGHT);
  }

  @Test
  @DisplayName("COMPARE → 013, Grok 결정이면 tier=LLM_LIGHT (013 도 history 의존이라 룰로 안 잡힘)")
  void compareToCode() {
    IntentResult r = adapter.adapt(ExtractionResult.compare(), false, List.of(), false);
    assertThat(r.code()).isEqualTo(IntentCode.COMPARE);
    assertThat(r.tier()).isEqualTo(IntentResult.Tier.LLM_LIGHT);
  }

  @Test
  @DisplayName("ruleDecided=true → tier=RULE, false → tier=LLM_LIGHT")
  void tierByDecider() {
    assertThat(adapter.adapt(ExtractionResult.skip(), true, List.of(), false).tier())
        .isEqualTo(IntentResult.Tier.RULE);
    assertThat(adapter.adapt(ExtractionResult.newSearch("k"), false, List.of(), false).tier())
        .isEqualTo(IntentResult.Tier.LLM_LIGHT);
  }

  @Test
  @DisplayName("앵커 슬롯(referencedImages) 전달")
  void passesAnchorSlot() {
    IntentResult r = adapter.adapt(ExtractionResult.newSearch("k"), false, List.of(2, 3), false);
    assertThat(r.referencedImages()).containsExactly(2, 3);
    assertThat(r.hasReferencedImages()).isTrue();
  }

  @Test
  @DisplayName("null referencedImages → 빈 리스트 (불변)")
  void nullAnchorBecomesEmpty() {
    IntentResult r = adapter.adapt(ExtractionResult.skip(), true, null, false);
    assertThat(r.referencedImages()).isEmpty();
    assertThat(r.hasReferencedImages()).isFalse();
  }

  @Test
  @DisplayName("hasUploadedImage 플래그 전달 (010 트리거 정보)")
  void passesUploadFlag() {
    assertThat(adapter.adapt(ExtractionResult.keep(), false, List.of(), true).hasUploadedImage())
        .isTrue();
    assertThat(adapter.adapt(ExtractionResult.keep(), false, List.of(), false).hasUploadedImage())
        .isFalse();
  }

  @Test
  @DisplayName("미분류 KEEP → 006")
  void keepWithoutArtIntent() {
    assertThat(adapter.adapt(ExtractionResult.keep(), false, List.of(), false).code())
        .isEqualTo(IntentCode.KEEP);
  }

  @Test
  @DisplayName("미술 의도 세분류된 KEEP → 001~004 (②-2차)")
  void keepWithArtIntent() {
    assertThat(
            adapter
                .adapt(ExtractionResult.keep(IntentCode.COMPOSITION), false, List.of(), false)
                .code())
        .isEqualTo(IntentCode.COMPOSITION);
    assertThat(
            adapter
                .adapt(ExtractionResult.keep(IntentCode.LIGHTING), false, List.of(), false)
                .code())
        .isEqualTo(IntentCode.LIGHTING);
    assertThat(
            adapter.adapt(ExtractionResult.keep(IntentCode.COLOR), false, List.of(), false).code())
        .isEqualTo(IntentCode.COLOR);
    assertThat(
            adapter
                .adapt(ExtractionResult.keep(IntentCode.TECHNIQUE), false, List.of(), false)
                .code())
        .isEqualTo(IntentCode.TECHNIQUE);
  }

  @Test
  @DisplayName("미술 의도 분류된 KEEP 도 tier 는 그대로 (Grok 결정이면 LLM_LIGHT)")
  void keepArtIntentTier() {
    assertThat(
            adapter.adapt(ExtractionResult.keep(IntentCode.COLOR), false, List.of(), false).tier())
        .isEqualTo(IntentResult.Tier.LLM_LIGHT);
  }

  @Test
  @DisplayName("adaptSelfCritique → 010, hasUploadedImage=true, tier=RULE")
  void selfCritique() {
    IntentResult r = adapter.adaptSelfCritique(List.of());
    assertThat(r.code()).isEqualTo(IntentCode.SELF_CRITIQUE);
    assertThat(r.hasUploadedImage()).isTrue();
    assertThat(r.tier()).isEqualTo(IntentResult.Tier.RULE);
    assertThat(r.referencedImages()).isEmpty();
  }

  @Test
  @DisplayName("adaptSelfCritique 앵커 슬롯 전달 + null → 빈 리스트")
  void selfCritiqueAnchor() {
    assertThat(adapter.adaptSelfCritique(List.of(2)).referencedImages()).containsExactly(2);
    assertThat(adapter.adaptSelfCritique(null).referencedImages()).isEmpty();
  }

  @Test
  @DisplayName("adaptOutOfDomain → 000, tier=RULE, hasUploadedImage=false")
  void outOfDomain() {
    IntentResult r = adapter.adaptOutOfDomain();
    assertThat(r.code()).isEqualTo(IntentCode.OUT_OF_DOMAIN);
    assertThat(r.tier()).isEqualTo(IntentResult.Tier.RULE);
    assertThat(r.hasUploadedImage()).isFalse();
    assertThat(r.referencedImages()).isEmpty();
  }
}
