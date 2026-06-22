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

  /** StepContext 헬퍼 — keywords 채워서 만들기. */
  private StepContext newCtxWithKeywords(List<String> keywords) {
    return new StepContext(
        1L,
        1L,
        "session-1",
        null, // rawMessage
        "테스트 메시지", // cleanedMessage
        null, // intent
        null, // uploadedImageUrl
        null, // previousReferences
        keywords, // keywords ⭐
        null, // references
        null, // generatedImage
        null // composedAnswer
        );
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
