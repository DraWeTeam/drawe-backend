package com.drawe.backend.domain.llm.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchRequest;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.domain.search.service.SearchService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SearchExecutor 단위 테스트.
 *
 * <p>실 검색 로직은 {@code SearchService} 에 있음. 이 테스트는 wrap 의 책임만: 위임, 어댑터 변환, 빈 입력 처리, 1-based index.
 */
class SearchExecutorTest {

  /**
   * StepContext 헬퍼 — keywords 채워서 만들기. start() 팩토리 + withKeywords 로 압축해 record 필드가 더 늘어도 안 깨지게 한다.
   * (withKeywords 는 null 도 그대로 보존 — keywords null 케이스 검증용.)
   */
  private StepContext newCtxWithKeywords(List<String> keywords) {
    return StepContext.start(
            1L,
            1L,
            "session-1",
            null, // rawMessage
            "테스트 메시지", // cleanedMessage
            null, // intent
            null, // uploadedImageUrl
            null) // previousReferences
        .withKeywords(keywords);
  }

  /** ImageResult 13개 필드를 매번 채우는 보일러플레이트 압축. */
  private ImageResult newImageResult(
      Long id,
      String url,
      String photographerName,
      Float score,
      String technique,
      String subject,
      String mood) {
    return new ImageResult(
        id,
        "src-" + id, // sourceId
        url,
        "user-" + id, // photographerUsername
        photographerName,
        score,
        technique,
        subject,
        mood,
        List.of(), // utility
        List.of(), // freeTags
        List.of(), // rawTags
        "pexels" // source
        );
  }

  @Test
  @DisplayName("type() = SEARCH")
  void typeIsSearch() {
    var searchService = mock(SearchService.class);
    var sut = new SearchExecutor(searchService);

    assertThat(sut.type()).isEqualTo(StepType.SEARCH);
  }

  @Test
  @DisplayName("execute() — 키워드 없음 → SearchService 호출 X, 빈 references")
  void skipWhenNoKeywords() {
    var searchService = mock(SearchService.class);
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of()));

    verify(searchService, never()).search(any(SearchRequest.class));
    assertThat(result.references()).isEmpty();
  }

  @Test
  @DisplayName("execute() — keywords null → SearchService 호출 X, 빈 references")
  void skipWhenKeywordsNull() {
    var searchService = mock(SearchService.class);
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(null));

    verify(searchService, never()).search(any(SearchRequest.class));
    assertThat(result.references()).isEmpty();
  }

  @Test
  @DisplayName("execute() — 키워드 있음 → 검색 + ReferenceImage 변환 + 1-based index")
  void searchAndAdapt() {
    var searchService = mock(SearchService.class);

    ImageResult r1 =
        newImageResult(
            1L, "https://example.com/1.jpg", "Alice", 0.95f, "watercolor", "landscape", "calm");
    ImageResult r2 =
        newImageResult(
            2L,
            "https://example.com/2.jpg",
            "Bob",
            0.92f,
            "watercolor",
            "mountain",
            null // mood null 케이스
            );

    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r1, r2), 2, "watercolor landscape"));

    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("watercolor", "landscape")));

    List<ReferenceImage> refs = result.references();
    assertThat(refs).hasSize(2);

    // 1-based index
    assertThat(refs.get(0).index()).isEqualTo(1);
    assertThat(refs.get(1).index()).isEqualTo(2);

    // 필드 매핑
    assertThat(refs.get(0).imageId()).isEqualTo(1L);
    assertThat(refs.get(0).url()).isEqualTo("https://example.com/1.jpg");
    assertThat(refs.get(0).photographer()).isEqualTo("Alice");
    assertThat(refs.get(0).score()).isEqualByComparingTo(BigDecimal.valueOf(0.95f));

    // tags 합산 (technique + subject + mood) — null 필터
    assertThat(refs.get(0).tags()).containsExactly("watercolor", "landscape", "calm");
    assertThat(refs.get(1).tags()).containsExactly("watercolor", "mountain");
  }

  @Test
  @DisplayName("execute() — 표시필드(photographerUsername·technique·subject·mood·source) 복원")
  void restoresDisplayFields() {
    var searchService = mock(SearchService.class);
    ImageResult r =
        newImageResult(
            1L, "https://example.com/1.jpg", "Alice", 0.9f, "watercolor", "landscape", "calm");
    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r), 1, "watercolor"));
    var sut = new SearchExecutor(searchService);

    ReferenceImage ref = sut.execute(newCtxWithKeywords(List.of("watercolor"))).references().get(0);

    assertThat(ref.photographerUsername()).isEqualTo("user-1");
    assertThat(ref.technique()).isEqualTo("watercolor");
    assertThat(ref.subject()).isEqualTo("landscape");
    assertThat(ref.mood()).isEqualTo("calm");
    assertThat(ref.source()).isEqualTo("pexels");
  }

  @Test
  @DisplayName("점수가드 — avg<0.2 AND max<0.24 (둘 다 낮음) 이면 references 차단 + blocked=low_score")
  void scoreGuardBlocksLowScore() {
    var searchService = mock(SearchService.class);
    // avg≈0.11, max=0.12 → avg·max 둘 다 낮음 → 차단
    ImageResult r1 = newImageResult(1L, "u1", "A", 0.10f, "t", "s", "m");
    ImageResult r2 = newImageResult(2L, "u2", "B", 0.12f, "t", "s", "m");
    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r1, r2), 2, "kw"));
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("kw")));

    // 차단 → references 빔
    assertThat(result.references()).isEmpty();
    // searchStats 는 통계·차단판정을 운반
    assertThat(result.searchStats()).isNotNull();
    assertThat(result.searchStats().blocked()).isTrue();
    assertThat(result.searchStats().blockedReason()).isEqualTo("low_score");
    assertThat(result.searchStats().resultCount()).isEqualTo(2);
    assertThat(result.searchStats().imageIds()).containsExactly(1L, 2L);
  }

  @Test
  @DisplayName("점수가드 — 점수 충분하면 통과 + searchStats.blocked=false")
  void scoreGuardPassesHighScore() {
    var searchService = mock(SearchService.class);
    ImageResult r = newImageResult(1L, "u1", "A", 0.5f, "t", "s", "m");
    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r), 1, "kw"));
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("kw")));

    assertThat(result.references()).hasSize(1);
    assertThat(result.searchStats().blocked()).isFalse();
    assertThat(result.searchStats().blockedReason()).isNull();
  }

  @Test
  @DisplayName("점수가드 rescue — avg<0.2 지만 max≥0.24 면 통과 (상위 1장 관련 있으면 살림, 베타 튜닝)")
  void scoreGuardRescuesHighMaxLowAvg() {
    var searchService = mock(SearchService.class);
    // 베타 실케이스 모사(man in suit): max=0.255, 나머지 낮음 → avg=0.189(<0.2) 지만 max≥0.24 → 통과
    ImageResult r1 = newImageResult(1L, "u1", "A", 0.255f, "t", "s", "m");
    ImageResult r2 = newImageResult(2L, "u2", "B", 0.123f, "t", "s", "m");
    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r1, r2), 2, "kw"));
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("kw")));

    // avg≈0.189 < 0.2 이지만 max=0.255 ≥ 0.24 → AND 가드 불충족 → 통과
    assertThat(result.references()).hasSize(2);
    assertThat(result.searchStats().blocked()).isFalse();
    assertThat(result.searchStats().blockedReason()).isNull();
  }

  @Test
  @DisplayName("점수가드 — avg≥0.2 면 max 와 무관하게 통과 (AND 라 avg 조건만 깨져도 통과)")
  void scoreGuardPassesWhenAvgHighEvenIfMaxLow() {
    var searchService = mock(SearchService.class);
    // avg=0.205(≥0.2), max=0.21(<0.24) → AND 불충족 → 통과
    ImageResult r1 = newImageResult(1L, "u1", "A", 0.21f, "t", "s", "m");
    ImageResult r2 = newImageResult(2L, "u2", "B", 0.20f, "t", "s", "m");
    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r1, r2), 2, "kw"));
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("kw")));

    assertThat(result.references()).hasSize(2);
    assertThat(result.searchStats().blocked()).isFalse();
  }

  @Test
  @DisplayName("점수가드 — 검색 결과 0건도 차단(low_score) — 레거시 동등 (b61c6cf, AND 에서도 avg=max=0 차단)")
  void scoreGuardBlocksEmptyResults() {
    var searchService = mock(SearchService.class);
    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(), 0, "kw"));
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("kw")));

    // 결과 0건 → avg=max=0.0 → 0<0.2 && 0<0.24 충족 → blocked(low_score). 과거엔 EXECUTED 로 새던 케이스.
    assertThat(result.references()).isEmpty();
    assertThat(result.searchStats().blocked()).isTrue();
    assertThat(result.searchStats().blockedReason()).isEqualTo("low_score");
    assertThat(result.searchStats().resultCount()).isZero();
  }

  @Test
  @DisplayName("검색 예외 — 삼키고 빈 references + searchStats.blocked=exception(error_class 운반)")
  void searchExceptionBlocksWithExceptionReason() {
    var searchService = mock(SearchService.class);
    when(searchService.search(any(SearchRequest.class)))
        .thenThrow(new IllegalStateException("pinecone down"));
    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("kw")));

    // 예외를 던지지 않고(워크플로 중단 방지) 빈 references 로 진행 — 레거시 catch 와 동등.
    assertThat(result.references()).isEmpty();
    assertThat(result.searchStats()).isNotNull();
    assertThat(result.searchStats().blocked()).isTrue();
    assertThat(result.searchStats().blockedReason()).isEqualTo("exception");
    assertThat(result.searchStats().errorClass()).isEqualTo("IllegalStateException");
  }

  @Test
  @DisplayName("execute() — utility·freeTags 도 tags 에 합산")
  void tagsIncludeUtilityAndFreeTags() {
    var searchService = mock(SearchService.class);

    ImageResult r =
        new ImageResult(
            1L,
            "src-1",
            "https://example.com/1.jpg",
            "user-1",
            "Alice",
            0.9f,
            "watercolor", // technique
            "landscape", // subject
            "calm", // mood
            List.of("reference"), // utility
            List.of("nature", "spring"), // freeTags
            List.of("raw-ignored"), // rawTags (제외됨)
            "pexels" // source (제외됨)
            );

    when(searchService.search(any(SearchRequest.class)))
        .thenReturn(new SearchResponse(List.of(r), 1, "watercolor"));

    var sut = new SearchExecutor(searchService);

    StepContext result = sut.execute(newCtxWithKeywords(List.of("watercolor")));

    ReferenceImage ref = result.references().get(0);
    assertThat(ref.tags())
        .containsExactly("watercolor", "landscape", "calm", "reference", "nature", "spring");
    assertThat(ref.tags()).doesNotContain("raw-ignored", "pexels");
  }
}
