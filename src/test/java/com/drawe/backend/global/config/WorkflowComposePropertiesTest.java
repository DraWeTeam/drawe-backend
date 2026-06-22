package com.drawe.backend.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.drawe.backend.domain.llm.contract.IntentCode;
import java.util.EnumSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * WorkflowComposeProperties 부팅 검증 단위 테스트(R1 방어).
 *
 * <p>핵심: live-intents 에 COMPOSE 미종착 의도가 설정되면 부팅(@PostConstruct validateLiveIntents)에서 즉시 실패해야 한다 —
 * 런타임 500(composedOutput=null) 대신 fail-fast.
 */
class WorkflowComposePropertiesTest {

  private WorkflowComposeProperties propsWith(IntentCode... codes) {
    WorkflowComposeProperties p = new WorkflowComposeProperties();
    EnumSet<IntentCode> set = EnumSet.noneOf(IntentCode.class);
    for (IntentCode c : codes) {
      set.add(c);
    }
    p.setLiveIntents(set);
    return p;
  }

  @Nested
  @DisplayName("부팅 검증 — 통과")
  class Valid {

    @Test
    @DisplayName("기본(빈 집합)은 통과 — 모든 의도 레거시")
    void emptyPasses() {
      WorkflowComposeProperties p = new WorkflowComposeProperties();
      assertThatCode(p::validateLiveIntents).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("COMPOSE 종착 의도(NEW_SEARCH/KEEP/SKIP)는 통과")
    void composeTerminatingPasses() {
      WorkflowComposeProperties p =
          propsWith(IntentCode.NEW_SEARCH, IntentCode.KEEP, IntentCode.SKIP);
      assertThatCode(p::validateLiveIntents).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("미술 의도 세분류(001~004)·OUT_OF_DOMAIN·SELF_CRITIQUE 도 COMPOSE 종착이라 통과")
    void artIntentsAndCritiquePass() {
      WorkflowComposeProperties p =
          propsWith(
              IntentCode.COMPOSITION,
              IntentCode.LIGHTING,
              IntentCode.COLOR,
              IntentCode.TECHNIQUE,
              IntentCode.OUT_OF_DOMAIN,
              IntentCode.SELF_CRITIQUE);
      assertThatCode(p::validateLiveIntents).doesNotThrowAnyException();
    }
  }

  @Nested
  @DisplayName("부팅 검증 — 실패(fail-fast)")
  class Invalid {

    @Test
    @DisplayName("GENERATE(=[TRANSLATE, GENERATE_IMAGE], COMPOSE 없음)는 부팅 실패")
    void generateFails() {
      WorkflowComposeProperties p = propsWith(IntentCode.GENERATE);
      assertThatThrownBy(p::validateLiveIntents)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GENERATE")
          .hasMessageContaining("COMPOSE 로 종착하지 않는");
    }

    @Test
    @DisplayName("안전 의도 + 위험 의도 섞이면 위험 의도만 짚어 부팅 실패")
    void mixedReportsOnlyInvalid() {
      WorkflowComposeProperties p = propsWith(IntentCode.NEW_SEARCH, IntentCode.GENERATE);
      assertThatThrownBy(p::validateLiveIntents)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("GENERATE");
    }
  }

  @Nested
  @DisplayName("isLive")
  class IsLive {

    @Test
    @DisplayName("설정된 의도만 live")
    void onlyConfiguredIsLive() {
      WorkflowComposeProperties p = propsWith(IntentCode.NEW_SEARCH);
      assertThat(p.isLive(IntentCode.NEW_SEARCH)).isTrue();
      assertThat(p.isLive(IntentCode.KEEP)).isFalse();
      assertThat(p.isLive(null)).isFalse();
    }
  }
}
