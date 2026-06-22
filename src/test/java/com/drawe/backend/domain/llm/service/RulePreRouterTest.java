package com.drawe.backend.domain.llm.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.llm.dto.ExtractionResult;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@link RulePreRouter} 룰 회귀 테스트. 설계: {@code docs/decisions/S1A-rule-prerouter-design.md}.
 *
 * <p>1차 범위 = TERMINAL(SKIP/GENERATE_NOW)만 룰 발화. NEW_SEARCH/KEEP/앵커/미술의도는 MISS 로 흘려 Grok 폴백.
 */
class RulePreRouterTest {

  private final RulePreRouter router = new RulePreRouter();

  private ExtractionResult.Action action(String message) {
    RulePreRouter.Decision d = router.route(message, List.of());
    return d.isHit() ? d.result().action() : null;
  }

  // ── GENERATE_NOW: 명시적 생성 동사 ────────────────────
  @ParameterizedTest
  @ValueSource(
      strings = {
        "강아지 그려줘",
        "고양이 만들어줘",
        "이미지 생성해줘",
        "한 번 그려줄래?",
        "비슷한 거 만들어줘", // 생성 동사 > 검색 신호 (우선순위 1)
        "그런 느낌으로 만들어 줘",
        "AI로 만들어",
        "generate a cat",
        "make it brighter"
      })
  @DisplayName("명시적 생성 동사 → GENERATE_NOW (LLM 0콜)")
  void generateVerbs(String message) {
    assertThat(action(message)).isEqualTo(ExtractionResult.Action.GENERATE_NOW);
  }

  @DisplayName("GENERATE_NOW 는 사용자 원문을 keywords 에 그대로 담는다 (PromptTranslator 가 번역)")
  @ParameterizedTest
  @ValueSource(strings = {"노을 지는 바다 그려줘", "draw it for me"})
  void generateKeepsRawMessage(String message) {
    RulePreRouter.Decision d = router.route(message, List.of());
    assertThat(d.isHit()).isTrue();
    assertThat(d.result().keywords()).isEqualTo(message.trim());
  }

  // ── SKIP: 짧은 단독 인사·감사 ─────────────────────────
  @ParameterizedTest
  @ValueSource(strings = {"고마워", "감사합니다", "ㄳ", "땡큐", "안녕", "ㅇㅋ", "굿", "좋아", "ㅋㅋㅋ", "thanks", "ok"})
  @DisplayName("짧은 단독 인사·감사 → SKIP")
  void thanksGreeting(String message) {
    assertThat(action(message)).isEqualTo(ExtractionResult.Action.SKIP);
  }

  @DisplayName("빈 메시지 → SKIP")
  @ParameterizedTest
  @ValueSource(strings = {"", "   "})
  void blank(String message) {
    assertThat(action(message)).isEqualTo(ExtractionResult.Action.SKIP);
  }

  // ── MISS: 룰이 안 잡고 Grok 으로 넘겨야 하는 것들 ───────
  @ParameterizedTest
  @ValueSource(
      strings = {
        "벚꽃 핀 봄 풍경 그리고 싶어요", // "그리고"가 생성어간 '그리'로 오발화하면 안 됨 (lookahead 방어)
        "풍경화 그리는 법 알려줘", // "그리는" → 생성 동사 아님, MISS
        "다른 레퍼런스 더 보여줘", // NEW_SEARCH → 1차 MISS
        "더 자세히 설명해줘", // KEEP
        "분홍색 그라데이션 어떻게 넣어요?", // KEEP/기법
        "1번 어떻게 그려?", // 앵커지만 기법 질문 → KEEP, 룰이 GENERATE 로 오발화하면 안 됨
        "이거 어떻게 그려?", // "그려?" 의문형 + 어떻게 → 방법 질문, GENERATE 아님
        "그림자 어떻게 만들어?", // 방법 질문
        "수채화 그리는 법", // 어간 '그리' 오발화 금지
        "이 구도 좋아 보이는데 어떻게 잡아요?", // "좋아" 포함하지만 문장 → SKIP 아님
        "보색이 뭐야?", // 이론 → SKIP 이지만 룰로 단정 못함, LLM
        "RGB와 CMYK 차이가 뭐야?"
      })
  @DisplayName("애매하거나 키워드 필요한 것 → MISS (Grok 폴백)")
  void miss(String message) {
    assertThat(router.route(message, List.of()).isHit()).isFalse();
  }

  @DisplayName("'좋아'가 문장에 섞이면 SKIP 오발화 금지 (단독성 검사)")
  @org.junit.jupiter.api.Test
  void greetingTokenInSentenceIsNotSkip() {
    // "좋아 보이는데..." 는 길이 > 10 이라 SKIP 룰이 발화하면 안 된다.
    assertThat(router.route("이 색감 좋아 보이는데 더 진하게 할까요?", List.of()).isHit()).isFalse();
  }

  @DisplayName("ruleId 가 메트릭용으로 채워진다")
  @org.junit.jupiter.api.Test
  void ruleIdPopulated() {
    assertThat(router.route("그려줘", List.of()).ruleId()).isEqualTo("generate_verb");
    assertThat(router.route("고마워", List.of()).ruleId()).isEqualTo("thanks_greeting");
    assertThat(router.route("벚꽃 풍경 그리고 싶어", List.of()).ruleId()).isEqualTo("miss");
  }

  // ── 010 SELF_CRITIQUE: 비평 요청 신호 (이미지 유무는 호출 측이 AND 결합) ───────
  @ParameterizedTest
  @ValueSource(
      strings = {
        "이거 어때?",
        "제 그림 평가해주세요",
        "피드백 주세요",
        "한번 봐줄래?",
        "고칠 점 있을까요?",
        "이거 잘 그렸어?",
        "괜찮아 보여?",
        "how's this?",
        "review my drawing",
        "feedback please"
      })
  @DisplayName("비평 요청 신호 → isCritiqueRequest=true (010 후보)")
  void critiqueRequestTrue(String message) {
    assertThat(router.isCritiqueRequest(message)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"벚꽃 풍경 그려줘", "다른 레퍼런스 보여줘", "수채화 기법 알려줘", "안녕하세요", "", "   "})
  @DisplayName("비평 신호 없는 일반 메시지 → isCritiqueRequest=false")
  void critiqueRequestFalse(String message) {
    assertThat(router.isCritiqueRequest(message)).isFalse();
  }

  // ── 000 OUT_OF_DOMAIN: 명백한 비미술 도메인 ─────────────
  @ParameterizedTest
  @ValueSource(
      strings = {
        "오늘 날씨 어때?",
        "비트코인 시세 알려줘",
        "파이썬으로 정렬 코드 짜줘",
        "근처 맛집 추천해줘",
        "어제 축구 경기 결과 알려줘",
        "이 영어 문장 번역해줘",
        "두통에 먹는 약 추천"
      })
  @DisplayName("명백한 비미술 도메인 → isOutOfDomain=true")
  void outOfDomainTrue(String message) {
    assertThat(router.isOutOfDomain(message)).isTrue();
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "벚꽃 풍경 그려줘", // 미술
        "수채화 기법 알려줘", // 미술
        "노을 색 그리려는데 날씨를 어떻게 표현해?", // 날씨 신호 있지만 그림 맥락 → 거절 X
        "이 그림 색감 어때?", // 미술
        "안녕하세요", // 인사(비미술이나 거절 대상 아님)
        "더 자세히 알려줘",
        "",
        "   "
      })
  @DisplayName("미술 맥락이 있거나 약한 신호 → isOutOfDomain=false (오탐 회피)")
  void outOfDomainFalse(String message) {
    assertThat(router.isOutOfDomain(message)).isFalse();
  }
}
