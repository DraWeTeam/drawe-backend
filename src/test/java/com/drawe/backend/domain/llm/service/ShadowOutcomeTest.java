package com.drawe.backend.domain.llm.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ChatLlmService#classifyShadowOutcome} 단위 테스트 (트랙 A ③ shadow 검증).
 *
 * <p>shadow 워크플로우(Komoran 경로)가 기존 baseline(Grok 키워드 경로) 검색결과를 얼마나 재현하는지 match/partial/miss 로 판정하는
 * 로직을 검증한다. 이 메트릭이 트랙 B 사전 품질의 실증 지표가 되므로 분류 경계가 정확해야 한다.
 */
class ShadowOutcomeTest {

  @Test
  @DisplayName("두 집합이 정확히 같으면 match")
  void exactMatch() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(1L, 2L, 3L), Set.of(1L, 2L, 3L)))
        .isEqualTo("match");
  }

  @Test
  @DisplayName("순서 무관하게 원소가 같으면 match (집합 비교)")
  void matchIsOrderIndependent() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(3L, 1L, 2L), Set.of(2L, 3L, 1L)))
        .isEqualTo("match");
  }

  @Test
  @DisplayName("교집합은 있지만 완전히 같지 않으면 partial")
  void partialOverlap() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(1L, 2L, 3L), Set.of(2L, 3L, 4L)))
        .isEqualTo("partial");
  }

  @Test
  @DisplayName("shadow 가 baseline 의 부분집합이면(겹치되 더 적음) partial")
  void shadowSubsetIsPartial() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(1L, 2L, 3L), Set.of(1L, 2L)))
        .isEqualTo("partial");
  }

  @Test
  @DisplayName("shadow 가 baseline 을 포함하되 더 많으면(상위집합) partial")
  void shadowSupersetIsPartial() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(1L, 2L), Set.of(1L, 2L, 3L)))
        .isEqualTo("partial");
  }

  @Test
  @DisplayName("교집합이 전혀 없으면 miss")
  void noOverlapIsMiss() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(1L, 2L), Set.of(3L, 4L)))
        .isEqualTo("miss");
  }

  @Test
  @DisplayName("shadow 가 비었으면 baseline 유무와 무관하게 miss")
  void emptyShadowIsMiss() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(1L, 2L), Set.of())).isEqualTo("miss");
  }

  @Test
  @DisplayName("baseline 도 shadow 도 비었으면 miss (match 로 오판하지 않음)")
  void bothEmptyIsMiss() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(), Set.of())).isEqualTo("miss");
  }

  @Test
  @DisplayName("baseline 이 비었는데 shadow 에 결과가 있으면 miss (교집합 0)")
  void emptyBaselineWithShadowIsMiss() {
    assertThat(ChatLlmService.classifyShadowOutcome(Set.of(), Set.of(1L, 2L))).isEqualTo("miss");
  }
}
