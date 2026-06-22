package com.drawe.backend.domain.llm.search;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import kr.co.shineware.nlp.komoran.model.Token;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/** KomoranKeywordExtractor 통합 테스트 (단계 1~4 완성). */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class KomoranKeywordExtractorTest {

  private static final Set<String> NOUN_TAGS = Set.of("NNG", "NNP");

  /** 테스트용 폴백 — 호출 기록 + 반환값 제어 가능. */
  private static class RecordingFallback implements KeywordExtractorFallback {
    final List<String> calls = new ArrayList<>();
    List<String> nextReturn = List.of();

    @Override
    public List<String> extract(String cleanedMessage) {
      calls.add(cleanedMessage);
      return nextReturn;
    }

    void reset() {
      calls.clear();
      nextReturn = List.of();
    }
  }

  private RecordingFallback fallback;
  private MeterRegistry registry;
  private KomoranKeywordExtractor extractor;

  @BeforeAll
  void setUp() {
    ArtTermsDictionary dictionary = new ArtTermsDictionary();
    dictionary.load();

    fallback = new RecordingFallback();
    registry = new SimpleMeterRegistry();
    extractor = new KomoranKeywordExtractor(dictionary, fallback, registry);
    extractor.init();
  }

  @BeforeEach
  void resetState() {
    fallback.reset();
  }

  // 메트릭 헬퍼 — 누적값 측정용
  private double hitCount() {
    var c = registry.find("drawe.dict.lookup").tag("result", "hit").counter();
    return c == null ? 0.0 : c.count();
  }

  private double missCount() {
    var c = registry.find("drawe.dict.lookup").tag("result", "miss").counter();
    return c == null ? 0.0 : c.count();
  }

  private double fallbackCount() {
    var c = registry.find("drawe.komoran.fallback").counter();
    return c == null ? 0.0 : c.count();
  }

  private long extractTimerCount() {
    var t = registry.find("drawe.komoran.extract").timer();
    return t == null ? 0L : t.count();
  }

  // ════════════════════════════════════════════════════════════
  // [단계 1] analyze() — raw 형태소 분석
  // ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("analyze — 빈 입력 → 빈 리스트")
  void analyzeEmpty() {
    assertThat(extractor.analyze(null)).isEmpty();
    assertThat(extractor.analyze("")).isEmpty();
  }

  @Test
  @DisplayName("analyze — 수채화 = 명사 (NNG 또는 NNP)")
  void analyzesBasicNoun() {
    List<Token> tokens = extractor.analyze("수채화");

    assertThat(tokens).anyMatch(t -> "수채화".equals(t.getMorph()) && NOUN_TAGS.contains(t.getPos()));
  }

  @Test
  @DisplayName("analyze — 사용자 사전: '레퍼런스' 보호")
  void userDicProtectsLoanword() {
    List<Token> tokens = extractor.analyze("역광 분위기의 인물 레퍼런스");

    assertThat(tokens).anyMatch(t -> "레퍼런스".equals(t.getMorph()) && NOUN_TAGS.contains(t.getPos()));
  }

  @Test
  @DisplayName("analyze — 사용자 사전: '그림' 명사 분해 방지")
  void userDicProtectsGrim() {
    List<Token> tokens = extractor.analyze("이 그림 구도가 어때");

    assertThat(tokens).anyMatch(t -> "그림".equals(t.getMorph()) && NOUN_TAGS.contains(t.getPos()));
  }

  // ════════════════════════════════════════════════════════════
  // [단계 2] extract() — 영문 키워드 추출 (3중 필터)
  // ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("extract — 빈 입력 → 빈 리스트, 폴백 X, Timer 호출 X")
  void extractEmpty() {
    long timerBefore = extractTimerCount();

    assertThat(extractor.extract(null)).isEmpty();
    assertThat(extractor.extract("")).isEmpty();

    assertThat(fallback.calls).isEmpty();
    // 빈 입력은 Timer 안 거치고 즉시 빈 리스트 (early return)
    assertThat(extractTimerCount()).isEqualTo(timerBefore);
  }

  @Test
  @DisplayName("extract — 수채화 풍경 → [watercolor, landscape]")
  void extractSimple() {
    List<String> keywords = extractor.extract("수채화 풍경");

    assertThat(keywords).containsExactlyInAnyOrder("watercolor", "landscape");
  }

  @Test
  @DisplayName("extract — 스톱워드 제거: '보여줘'의 '보이' 빠짐")
  void extractRemovesStopwords() {
    List<String> keywords = extractor.extract("수채화 보여줘");

    assertThat(keywords).containsExactly("watercolor");
  }

  @Test
  @DisplayName("extract — 검색 메타동사 '찾' 제거: 고양이 찾아줘 → [cat], 폴백 X")
  void extractRemovesSearchMetaVerb() {
    List<String> keywords = extractor.extract("고양이 찾아줘");

    // '찾'(찾다)이 스톱워드로 빠져 미스율 0 → 폴백 안 탐, 사전 hit만 반환
    assertThat(keywords).containsExactly("cat");
    assertThat(fallback.calls).isEmpty();
  }

  @Test
  @DisplayName("extract — PoC: 수채화로 그린 고양이 더 보여줘 → [watercolor, cat] ('그리'=draw 요청동사는 STOPWORD)")
  void extractPoCSample01() {
    // '그리/그려/그렸'(그리다=draw 요청동사)는 '주요 키워드만 추출' 방침으로 STOPWORD 처리 → drawing 미추출.
    // (명사 '그림'은 별개 형태소라 그대로 drawing 으로 추출됨.) 2026-06 트랙 B 결정 — A 싱크 대상.
    List<String> keywords = extractor.extract("수채화로 그린 고양이 더 보여줘");

    assertThat(keywords).contains("watercolor", "cat");
    assertThat(keywords).doesNotContain("drawing");
  }

  @Test
  @DisplayName("extract — 액션 동사: 달리는 고양이 → [running, cat]")
  void extractActionVerb() {
    List<String> keywords = extractor.extract("달리는 고양이");

    assertThat(keywords).containsExactlyInAnyOrder("running", "cat");
  }

  @Test
  @DisplayName("extract — 포즈 동사: 앉아 있는 강아지 → [sitting, puppy]")
  void extractPoseVerb() {
    List<String> keywords = extractor.extract("앉아 있는 강아지");

    assertThat(keywords).containsExactlyInAnyOrder("sitting", "puppy");
  }

  @Test
  @DisplayName("extract — 중복 제거: 고양이 고양이 → [cat]")
  void extractDeduplicates() {
    List<String> keywords = extractor.extract("고양이 고양이");

    assertThat(keywords).containsExactly("cat");
  }

  // ════════════════════════════════════════════════════════════
  // [단계 3] LLM 폴백
  // ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("폴백 X — 100% hit")
  void noFallbackWhenAllHits() {
    List<String> keywords = extractor.extract("수채화 풍경 명암");

    assertThat(keywords).containsExactlyInAnyOrder("watercolor", "landscape", "light and shadow");
    assertThat(fallback.calls).isEmpty();
  }

  @Test
  @DisplayName("폴백 발동 — 100% miss → 폴백 결과 반환")
  void fallbackWhenMostMisses() {
    fallback.nextReturn = List.of("code", "design", "algorithm");

    List<String> keywords = extractor.extract("코드 디자인 알고리즘");

    assertThat(fallback.calls).hasSize(1);
    assertThat(fallback.calls.get(0)).isEqualTo("코드 디자인 알고리즘");
    assertThat(keywords).containsExactly("code", "design", "algorithm");
  }

  @Test
  @DisplayName("폴백 X — 25% miss (경계)")
  void noFallbackAtBoundary() {
    List<String> keywords = extractor.extract("수채화 풍경 명암 신단어");

    assertThat(fallback.calls).isEmpty();
    assertThat(keywords).contains("watercolor", "landscape", "light and shadow");
  }

  @Test
  @DisplayName("폴백 X — 어간 0개")
  void noFallbackWhenNoStems() {
    List<String> keywords = extractor.extract("그래");

    assertThat(keywords).isEmpty();
    assertThat(fallback.calls).isEmpty();
  }

  // ════════════════════════════════════════════════════════════
  // [단계 4] Micrometer 메트릭 ⭐
  // ════════════════════════════════════════════════════════════

  @Test
  @DisplayName("메트릭 — hit 카운터 증가")
  void metricHitIncremented() {
    double before = hitCount();
    extractor.extract("수채화 풍경"); // 2 hits
    double after = hitCount();

    assertThat(after - before).isEqualTo(2.0);
  }

  @Test
  @DisplayName("메트릭 — miss 카운터 증가 + 폴백 카운터 증가")
  void metricMissAndFallbackIncremented() {
    double missBefore = missCount();
    double fallbackBefore = fallbackCount();

    fallback.nextReturn = List.of("dummy");
    extractor.extract("코드 디자인 알고리즘"); // 3 miss → 폴백

    assertThat(missCount() - missBefore).as("3 misses").isEqualTo(3.0);
    assertThat(fallbackCount() - fallbackBefore).as("1 폴백").isEqualTo(1.0);
  }

  @Test
  @DisplayName("메트릭 — extract Timer 카운트 증가")
  void metricTimerIncremented() {
    long before = extractTimerCount();

    extractor.extract("수채화");
    extractor.extract("고양이");
    extractor.extract("풍경");

    assertThat(extractTimerCount() - before).isEqualTo(3L);
  }

  @Test
  @DisplayName("메트릭 — 시작 시점에 카운터 미리 등록 (0값으로 노출)")
  void metricsRegisteredAtStartup() {
    // PostConstruct 가 메트릭 미리 만들었어야
    assertThat(registry.find("drawe.dict.lookup").tag("result", "hit").counter())
        .as("hit counter pre-registered")
        .isNotNull();
    assertThat(registry.find("drawe.dict.lookup").tag("result", "miss").counter())
        .as("miss counter pre-registered")
        .isNotNull();
    assertThat(registry.find("drawe.komoran.fallback").counter())
        .as("fallback counter pre-registered")
        .isNotNull();
    assertThat(registry.find("drawe.komoran.extract").timer())
        .as("extract timer pre-registered")
        .isNotNull();
  }
}
