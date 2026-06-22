package com.drawe.backend.domain.llm.search;

import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchRequest;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.domain.search.service.SearchService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * SEARCH 단계 실행기 — 기존 베타 SearchService 호출 + 어댑터.
 *
 * <p>2가지 작업:
 *
 * <ol>
 *   <li>SearchService 호출 (기존 베타 CLIP 검색 재사용)
 *   <li>ImageResult → ReferenceImage 어댑터 변환 (search 도메인 → contract 패키지 의존 차단)
 * </ol>
 *
 * <p>인용 무결성: 1-based index 부여 (사용자가 보는 [1], [2] 와 매칭).
 *
 * <p>tags 합산 정책: technique·subject·mood·utility·freeTags 합산. rawTags / sourceId /
 * photographerUsername / source 는 제외 (사용자 노출 X).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SearchExecutor implements StepExecutor {

  /** 기본 topK — A의 IntentClassifier 가 변경할 여지 두지만 일단 10. */
  private static final int DEFAULT_TOP_K = 10;

  private final SearchService searchService;

  @Override
  public StepType type() {
    return StepType.SEARCH;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    List<String> keywords = ctx.keywords();

    if (keywords == null || keywords.isEmpty()) {
      log.debug("SEARCH skipped — no keywords");
      return ctx.withReferences(List.of());
    }

    // 기존 SearchService 호출
    SearchRequest req = buildRequest(keywords);
    SearchResponse resp = searchService.search(req);

    // ImageResult → ReferenceImage 변환 (1-based index)
    List<ImageResult> results = resp.results();
    List<ReferenceImage> refs =
        IntStream.range(0, results.size())
            .mapToObj(i -> toReferenceImage(results.get(i), i + 1))
            .toList();

    if (log.isDebugEnabled()) {
      log.debug("SEARCH: keywords={} → {} refs", keywords, refs.size());
    }

    return ctx.withReferences(refs);
  }

  /** SearchRequest 생성 — query 는 키워드 space-join, topK 는 기본 10. */
  private SearchRequest buildRequest(List<String> keywords) {
    return new SearchRequest(String.join(" ", keywords), DEFAULT_TOP_K);
  }

  /**
   * ImageResult → ReferenceImage 어댑터.
   *
   * <p>주의:
   *
   * <ul>
   *   <li>score 는 {@code Float} → {@code BigDecimal} 변환 시 {@code doubleValue()} 거침
   *   <li>tags = technique·subject·mood (String) + utility·freeTags (List) 합산, null 필터
   * </ul>
   */
  private ReferenceImage toReferenceImage(ImageResult r, int index) {
    return new ReferenceImage(
        r.id(),
        index,
        r.url(),
        r.photographerName(),
        BigDecimal.valueOf(r.score().doubleValue()),
        collectTags(r));
  }

  private static List<String> collectTags(ImageResult r) {
    List<String> tags = new ArrayList<>();
    if (r.technique() != null) {
      tags.add(r.technique());
    }
    if (r.subject() != null) {
      tags.add(r.subject());
    }
    if (r.mood() != null) {
      tags.add(r.mood());
    }
    if (r.utility() != null) {
      tags.addAll(r.utility());
    }
    if (r.freeTags() != null) {
      tags.addAll(r.freeTags());
    }
    return tags;
  }
}
