package com.drawe.backend.domain.llm.search;

import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.SearchStats;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchRequest;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.domain.search.service.SearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * SEARCH 단계 실행기 — 기존 베타 SearchService 호출 + 어댑터.
 *
 * <p>2가지 작업:
 * <ol>
 *   <li>SearchService 호출 (기존 베타 CLIP 검색 재사용)</li>
 *   <li>ImageResult → ReferenceImage 어댑터 변환
 *       (search 도메인 → contract 패키지 의존 차단)</li>
 * </ol>
 *
 * <p>인용 무결성: 1-based index 부여 (사용자가 보는 [1], [2] 와 매칭).
 *
 * <p>tags 합산 정책: technique·subject·mood·utility·freeTags 합산.
 * rawTags / sourceId / photographerUsername / source 는 제외 (사용자 노출 X).
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

    /** 점수 가드 임계 — 레거시 handleSearchDecision 과 동일. 무관 결과 차단. */
    private static final double AVG_SCORE_FLOOR = 0.2;
    private static final double MAX_SCORE_FLOOR = 0.21;

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
        List<ImageResult> results = resp.results();

        // 점수 통계 (레거시 handleSearchDecision 이관). 결과 0 이면 0.0.
        double avg = results.stream().mapToDouble(r -> r.score().doubleValue()).average().orElse(0.0);
        double max = results.stream().mapToDouble(r -> r.score().doubleValue()).max().orElse(0.0);
        double min = results.stream().mapToDouble(r -> r.score().doubleValue()).min().orElse(0.0);

        List<Long> imageIds = results.stream().map(ImageResult::id).toList();
        List<Double> scores =
                results.stream().map(r -> round3(r.score().doubleValue())).toList();

        // 점수 가드 — avg<0.2 || max<0.21 이면 무관 결과로 보고 차단(references 비움).
        // analytics(SEARCH_EXECUTED/BLOCKED) 발사는 Executor 가 아니라 chatViaWorkflow 가 searchStats 보고 한다
        // (Executor 순수성 유지 + shadow 중복 방지).
        boolean blocked = !results.isEmpty() && (avg < AVG_SCORE_FLOOR || max < MAX_SCORE_FLOOR);

        SearchStats stats =
                new SearchStats(
                        req.query(),
                        results.size(),
                        round3(avg),
                        round3(max),
                        round3(min),
                        blocked,
                        blocked ? "low_score" : null,
                        imageIds,
                        scores);

        if (blocked) {
            log.info(
                    "SEARCH 점수가드 차단: avg={} max={} (floor avg={}, max={}), count={}",
                    String.format("%.3f", avg),
                    String.format("%.3f", max),
                    AVG_SCORE_FLOOR,
                    MAX_SCORE_FLOOR,
                    results.size());
            return ctx.withReferences(List.of()).withSearchStats(stats);
        }

        // ImageResult → ReferenceImage 변환 (1-based index)
        List<ReferenceImage> refs = IntStream.range(0, results.size())
                .mapToObj(i -> toReferenceImage(results.get(i), i + 1))
                .toList();

        if (log.isDebugEnabled()) {
            log.debug("SEARCH: keywords={} → {} refs (avg={}, max={})", keywords, refs.size(), avg, max);
        }

        return ctx.withReferences(refs).withSearchStats(stats);
    }

    private static double round3(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    /**
     * SearchRequest 생성 — query 는 키워드 space-join, topK 는 기본 10.
     */
    private SearchRequest buildRequest(List<String> keywords) {
        return new SearchRequest(String.join(" ", keywords), DEFAULT_TOP_K);
    }

    /**
     * ImageResult → ReferenceImage 어댑터.
     *
     * <p>주의:
     * <ul>
     *   <li>score 는 {@code Float} → {@code BigDecimal} 변환 시 {@code doubleValue()} 거침</li>
     *   <li>tags = technique·subject·mood (String) + utility·freeTags (List) 합산, null 필터</li>
     *   <li>표시 필드(photographerUsername·technique·subject·mood·source)는 live 경로
     *       ChatResponse 매핑이 레거시 수준으로 복원하도록 그대로 실어 보낸다(인용 무결성과 무관).</li>
     * </ul>
     */
    private ReferenceImage toReferenceImage(ImageResult r, int index) {
        return new ReferenceImage(
                r.id(),
                index,
                r.url(),
                r.photographerName(),
                BigDecimal.valueOf(r.score().doubleValue()),
                collectTags(r),
                r.photographerUsername(),
                r.technique(),
                r.subject(),
                r.mood(),
                r.source()
        );
    }

    private static List<String> collectTags(ImageResult r) {
        List<String> tags = new ArrayList<>();
        if (r.technique() != null) tags.add(r.technique());
        if (r.subject() != null) tags.add(r.subject());
        if (r.mood() != null) tags.add(r.mood());
        if (r.utility() != null) tags.addAll(r.utility());
        if (r.freeTags() != null) tags.addAll(r.freeTags());
        return tags;
    }
}