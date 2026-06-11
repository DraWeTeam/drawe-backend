package com.drawe.backend.domain.llm.output;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.llm.contract.ReferenceImage;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * OutputIntegrityChecker 단위 테스트(설계 §5, ADR §6.3) — 결정론적 참조 무결성.
 *
 * <p>DoD: 환각 인용 0건. 유효 범위 밖 인용은 본문 {@code [N]}·citations 양쪽에서 제거되고 응답은 통과해야 한다
 * (문장 전체 삭제 아님). refs 가 없으면 모든 인용이 환각.
 */
class OutputIntegrityCheckerTest {

  private final OutputIntegrityChecker checker = new OutputIntegrityChecker();

  private static List<ReferenceImage> refs(int count) {
    List<ReferenceImage> list = new ArrayList<>();
    for (int i = 1; i <= count; i++) {
      list.add(new ReferenceImage((long) i, i, "u" + i, null, BigDecimal.ONE, List.of()));
    }
    return list;
  }

  private static ComposedOutput out(String message, List<Integer> citations) {
    return new ComposedOutput(message, citations, false);
  }

  @Nested
  @DisplayName("환각 없음 — 통과")
  class Clean {

    @Test
    @DisplayName("모든 인용이 유효하면 본문·citations 보존, 위반 0")
    void allValid() {
      IntegrityResult r = checker.check(out("수채화 [1] 와 펜화 [2] 추천", List.of(1, 2)), refs(2));

      assertThat(r.output().message()).isEqualTo("수채화 [1] 와 펜화 [2] 추천");
      assertThat(r.output().citations()).containsExactly(1, 2);
      assertThat(r.hadHallucination()).isFalse();
      assertThat(r.hallucinatedCitations()).isZero();
      assertThat(r.hallucinatedBodyTokens()).isZero();
    }

    @Test
    @DisplayName("citations 중복/역순은 오름차순·중복제거로 정규화(위반 아님)")
    void normalizesCitations() {
      IntegrityResult r = checker.check(out("[2] 그리고 [1]", List.of(2, 1, 2)), refs(2));

      assertThat(r.output().citations()).containsExactly(1, 2);
      assertThat(r.hadHallucination()).isFalse();
    }
  }

  @Nested
  @DisplayName("환각 인용 제거 — DoD 0건")
  class Hallucination {

    @Test
    @DisplayName("범위 밖 citations 인덱스 제거, 유효한 것만 남음")
    void dropsOutOfRangeCitations() {
      IntegrityResult r = checker.check(out("안내", List.of(1, 4, 9)), refs(2));

      assertThat(r.output().citations()).containsExactly(1);
      assertThat(r.hallucinatedCitations()).isEqualTo(2);
    }

    @Test
    @DisplayName("본문 환각 토큰만 제거하고 유효 토큰·문장은 보존")
    void removesHallucinatedBodyTokenOnly() {
      IntegrityResult r = checker.check(out("수채화 [1] 와 없는것 [4] 추천", List.of(1, 4)), refs(2));

      // [4] 제거, [1] 보존. 토큰 자리 공백 정리되어 흐름 보존.
      assertThat(r.output().message()).isEqualTo("수채화 [1] 와 없는것 추천");
      assertThat(r.output().citations()).containsExactly(1);
      assertThat(r.hallucinatedBodyTokens()).isEqualTo(1);
      assertThat(r.hallucinatedCitations()).isEqualTo(1);
      assertThat(r.hadHallucination()).isTrue();
    }

    @Test
    @DisplayName("단어 사이 환각 토큰 제거 — 붙은 단어가 자연스럽게 이어짐")
    void removesInlineToken() {
      IntegrityResult r = checker.check(out("기법[4]을 보세요", List.of()), refs(1));

      assertThat(r.output().message()).isEqualTo("기법을 보세요");
      assertThat(r.hallucinatedBodyTokens()).isEqualTo(1);
    }

    @Test
    @DisplayName("문장부호 직전 환각 토큰 제거 — 공백 잔재 없음")
    void removesTokenBeforePunctuation() {
      IntegrityResult r = checker.check(out("좋은 예시예요 [5].", List.of()), refs(1));

      assertThat(r.output().message()).isEqualTo("좋은 예시예요.");
      assertThat(r.hallucinatedBodyTokens()).isEqualTo(1);
    }
  }

  @Nested
  @DisplayName("references 없음 — 모든 인용이 환각(§5.1-5)")
  class NoRefs {

    @Test
    @DisplayName("refs 가 비어있으면 본문·citations 의 모든 인용 제거")
    void emptyRefsStripsAll() {
      IntegrityResult r = checker.check(out("[1] 과 [2] 를 보세요", List.of(1, 2)), List.of());

      assertThat(r.output().message()).isEqualTo("과 를 보세요");
      assertThat(r.output().citations()).isEmpty();
      assertThat(r.hallucinatedCitations()).isEqualTo(2);
      assertThat(r.hallucinatedBodyTokens()).isEqualTo(2);
    }

    @Test
    @DisplayName("refs 가 null 이어도 안전하게 전부 환각 처리")
    void nullRefs() {
      IntegrityResult r = checker.check(out("[1] 보세요", List.of(1)), null);

      assertThat(r.output().message()).isEqualTo("보세요");
      assertThat(r.output().citations()).isEmpty();
    }

    @Test
    @DisplayName("refs 없고 인용도 없으면 위반 0, 본문 그대로")
    void noRefsNoCitations() {
      IntegrityResult r = checker.check(out("자료가 없어 일반 조언을 드려요", List.of()), List.of());

      assertThat(r.output().message()).isEqualTo("자료가 없어 일반 조언을 드려요");
      assertThat(r.hadHallucination()).isFalse();
    }
  }

  @Test
  @DisplayName("offerGenerate 는 무결성 검사에서 보존")
  void preservesOfferGenerate() {
    ComposedOutput raw = new ComposedOutput("자료가 부족해요", List.of(), true);

    IntegrityResult r = checker.check(raw, List.of());

    assertThat(r.output().offerGenerate()).isTrue();
  }
}
