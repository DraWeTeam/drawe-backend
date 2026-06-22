package com.drawe.backend.domain.llm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PersonaRegistry 단위 테스트 (S2' 트랙 A ⑥, 설계 §6.1).
 *
 * <p>핵심: v2(FRIENDLY_02)가 structured output 인용 규칙을 강화해 추가됐고, v1 은 롤백용으로 보존되며 DEFAULT_KEY 는 아직 v1
 * 이다(전환은 A/B 관측 후 별도).
 */
class PersonaRegistryTest {

  private final PersonaRegistry registry = new PersonaRegistry();

  @Test
  @DisplayName("DEFAULT_KEY 는 v2(FRIENDLY_02) — 거절/인사 톤 완화 반영")
  void defaultKeyIsV2() {
    assertThat(PersonaRegistry.DEFAULT_KEY).isEqualTo("FRIENDLY_02");
  }

  @Test
  @DisplayName("v2(=기본) 는 거절을 다양화하고 인사·감사를 거절하지 않는다")
  void v2SoftensRefusalAndGreetings() {
    String v2 = registry.resolve(PersonaRegistry.DEFAULT_KEY);
    // 단일 고정 거절 문구가 아니라 상황별 다양화 지시
    assertThat(v2).contains("매번 똑같은 문장을 반복하지 말고");
    // 인사·감사는 거절 대상이 아님을 명시
    assertThat(v2).contains("인사·감사·가벼운 반응");
    assertThat(v2).contains("거절 대상이 절대 아니다");
  }

  @Test
  @DisplayName("v1 은 보존된다 — 롤백 가능")
  void v1Preserved() {
    String v1 = registry.resolve("FRIENDLY_01");
    assertThat(v1).contains("그림을 함께 그리는 친근한 옆자리 친구");
    assertThat(v1).contains("[도메인 락");
  }

  @Test
  @DisplayName("v2 는 인용 규칙을 structured output 과 정합하게 강화한다")
  void v2HardensCitationRules() {
    String v2 = registry.resolve(PersonaRegistry.V2_KEY);
    assertThat(v2).contains("[인용 규칙");
    // citations 배열 ↔ 본문 [N] 일치 / 범위 밖 번호 금지 / 참고 없으면 빈 배열
    assertThat(v2).contains("citations 배열과 본문 [N] 은 정확히 일치");
    assertThat(v2).contains("개수 밖의 번호를 쓰지 마라");
    assertThat(v2).contains("citations 는 빈 배열");
    // 톤·도메인락은 v1 과 동일하게 유지
    assertThat(v2).contains("그림을 함께 그리는 친근한 옆자리 친구");
    assertThat(v2).contains("[도메인 락");
  }

  @Test
  @DisplayName("null/blank 키는 DEFAULT_KEY 로 폴백")
  void blankFallsBackToDefault() {
    assertThat(registry.resolve(null)).isEqualTo(registry.resolve(PersonaRegistry.DEFAULT_KEY));
    assertThat(registry.resolve("  ")).isEqualTo(registry.resolve(PersonaRegistry.DEFAULT_KEY));
  }

  @Test
  @DisplayName("모르는 키는 예외")
  void unknownKeyThrows() {
    assertThatThrownBy(() -> registry.resolve("NOPE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Unknown persona key");
  }
}
