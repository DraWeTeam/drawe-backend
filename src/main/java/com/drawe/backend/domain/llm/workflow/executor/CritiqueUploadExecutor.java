package com.drawe.backend.domain.llm.workflow.executor;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.search.dto.ImageResult;
import com.drawe.backend.domain.search.dto.SearchResponse;
import com.drawe.backend.domain.search.service.SearchService;
import com.drawe.backend.global.client.FastApiClient;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * CRITIQUE_UPLOAD 단계 실행기 — 사용자가 업로드한 본인 작업물을 비평한다 (010 SELF_CRITIQUE).
 *
 * <p><b>1차(a1)</b>: 비평 모드 가이드를 SYSTEM turn 으로 주입해 후속 COMPOSE 가 멀티모달 비전으로 비평하게 했다.
 *
 * <p><b>2차(a2 — 유사 레퍼런스 첨부)</b>: 업로드 이미지를 CLIP 임베딩({@code FastApiClient.embedImage}) → {@code
 * SearchService.searchByVector} 로 비슷한 레퍼런스를 찾아 {@code ctx.references} 에 싣는다. COMPOSE 가 그 references
 * 로 [N] 인용 컨텍스트를 만들어 "네 그림과 비슷한 [1] 처럼 …" 식 비평이 된다. 임베딩은 텍스트 검색과 동일 CLIP 공간이라 그대로 비교 가능. 설계: {@code
 * docs/decisions/S3A-self-critique-design.md} §2.1·§7.
 *
 * <p>유사 검색은 부가가치라 <b>실패해도 비평 자체는 막지 않는다</b> — 임베딩/검색 예외나 저점수 차단 시 references 를 빈 채로 두고 a1 의 텍스트(비전)
 * 비평으로 폴백한다. 비평 가이드도 references 유무로 조건부 분기한다([N] 인용 허용/금지).
 *
 * <p>업로드 이미지가 없으면(방어) 아무것도 하지 않고 통과한다(못 본 이미지 비평 방지).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CritiqueUploadExecutor implements StepExecutor {

  /** 비평용 유사 레퍼런스 개수 — '핵심 비교 몇 장'이 적합(기본 검색 10보다 적게). 설계 §결정3. */
  private static final int CRITIQUE_TOP_K = 4;

  /** 점수 가드 임계 — SearchExecutor 와 동일. 동떨어진 무관 레퍼런스가 비평에 섞이지 않게 차단. */
  private static final double AVG_SCORE_FLOOR = 0.2;

  private static final double MAX_SCORE_FLOOR = 0.21;

  private final FastApiClient fastApiClient;
  private final SearchService searchService;

  /** references 가 있을 때(유사 레퍼런스 첨부) 비평 가이드 — [N] 인용을 허용한다. 무결성 체커가 범위 밖 인용을 정정하므로 환각은 여전히 차단된다. */
  static final String CRITIQUE_GUIDE_WITH_REFS =
      "[작업물 비평 안내]\n"
          + "사용자가 본인이 그린 작업물을 직접 올리고 평가를 요청했습니다. 첨부된 이미지를 보고 비평해주세요.\n"
          + "위 [참고 이미지]는 사용자 작업물과 시각적으로 비슷한 레퍼런스입니다.\n"
          + "\n"
          + "응답 가이드:\n"
          + "- 구도 / 명암 / 색 / 형태 관점에서 구체적이고 건설적인 피드백을 주세요.\n"
          + "- 잘된 점을 먼저 1가지 짚고, 개선하면 좋을 점을 1~2가지 제안하세요.\n"
          + "- 비슷한 레퍼런스를 [1], [2] 형식으로 인용해 비교하면 좋습니다. 예: \"[1]번처럼 빛 방향을 한쪽으로 모으면…\"\n"
          + "- 사용자가 평가를 직접 요청했으므로 이 답변에서는 솔직한 피드백이 허용됩니다(평소의 평가 자제 해제).\n"
          + "- 단정·강요는 하지 말고, \"이렇게 해보면 어떨까요?\" 같은 권유 톤을 유지하세요.\n"
          + "\n"
          + "금지:\n"
          + "- 사용자를 깎아내리거나 \"틀렸어요\" 같은 단정적 부정.\n"
          + "- 못 본 이미지를 본 척하거나, 만들지 않은 이미지를 만든 척하는 표현.\n"
          + "- 범위 밖 번호 인용([참고 이미지] 개수를 초과하는 [N]).";

  /** references 가 없을 때(임베딩/검색 실패·저점수) 비평 가이드 — [N] 인용 금지(a1 과 동일). */
  static final String CRITIQUE_GUIDE_NO_REFS =
      "[작업물 비평 안내]\n"
          + "사용자가 본인이 그린 작업물을 직접 올리고 평가를 요청했습니다. 첨부된 이미지를 보고 비평해주세요.\n"
          + "\n"
          + "응답 가이드:\n"
          + "- 구도 / 명암 / 색 / 형태 관점에서 구체적이고 건설적인 피드백을 주세요.\n"
          + "- 잘된 점을 먼저 1가지 짚고, 개선하면 좋을 점을 1~2가지 제안하세요.\n"
          + "- 사용자가 평가를 직접 요청했으므로 이 답변에서는 솔직한 피드백이 허용됩니다(평소의 평가 자제 해제).\n"
          + "- 단정·강요는 하지 말고, \"이렇게 해보면 어떨까요?\" 같은 권유 톤을 유지하세요.\n"
          + "\n"
          + "금지:\n"
          + "- [1], [2] 같은 인용 표현 (지금은 비교할 참고 이미지가 없음).\n"
          + "- 사용자를 깎아내리거나 \"틀렸어요\" 같은 단정적 부정.\n"
          + "- 못 본 이미지를 본 척하거나, 만들지 않은 이미지를 만든 척하는 표현.";

  @Override
  public StepType type() {
    return StepType.CRITIQUE_UPLOAD;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    byte[] image = ctx.uploadedImageBytes();
    if (image == null || image.length == 0) {
      // 비평 대상 이미지 없음 — 가이드 주입 안 함(못 본 이미지 비평 방지). 컨텍스트 통과.
      log.warn("CRITIQUE_UPLOAD: 업로드 이미지 없음 — 비평 가이드 미주입, 통과");
      return ctx;
    }

    // a2: 유사 레퍼런스 검색(부가가치). 실패해도 비평을 막지 않으므로 예외를 삼켜 빈 refs 로 폴백.
    List<ReferenceImage> refs = findSimilarReferences(image, ctx.uploadedImageMimeType());

    List<LlmCallContext.Turn> history = new ArrayList<>(ctx.history());
    String guide = refs.isEmpty() ? CRITIQUE_GUIDE_NO_REFS : CRITIQUE_GUIDE_WITH_REFS;
    history.add(new LlmCallContext.Turn(MessageRole.SYSTEM, guide));
    log.debug("CRITIQUE_UPLOAD: 비평 가이드 주입 (bytes={}, refs={})", image.length, refs.size());

    return ctx.withHistory(history).withReferences(refs);
  }

  /**
   * 업로드 이미지 → CLIP 임베딩 → 유사 레퍼런스 검색 → 점수 가드 → ReferenceImage 변환(1-based). 임베딩/검색 예외나 저점수는 빈 리스트로 흘려
   * 비평 자체는 진행시킨다(부가가치 실패 격리).
   */
  private List<ReferenceImage> findSimilarReferences(byte[] imageBytes, String mimeType) {
    try {
      List<Float> vector =
          fastApiClient.embedImage(imageBytes, mimeType != null ? mimeType : "image/jpeg");
      SearchResponse resp = searchService.searchByVector(vector, CRITIQUE_TOP_K);
      List<ImageResult> results = resp.results();
      if (results.isEmpty()) {
        return List.of();
      }

      double avg = results.stream().mapToDouble(r -> r.score().doubleValue()).average().orElse(0.0);
      double max = results.stream().mapToDouble(r -> r.score().doubleValue()).max().orElse(0.0);
      if (avg < AVG_SCORE_FLOOR || max < MAX_SCORE_FLOOR) {
        log.info(
            "CRITIQUE_UPLOAD 유사검색 점수가드 차단: avg={} max={} count={} — 텍스트 비평 폴백",
            String.format("%.3f", avg),
            String.format("%.3f", max),
            results.size());
        return List.of();
      }

      return IntStream.range(0, results.size())
          .mapToObj(i -> toReferenceImage(results.get(i), i + 1))
          .toList();
    } catch (RuntimeException e) {
      // 임베딩/검색 실패 — 비평은 막지 않는다(부가가치). 레거시 검색 격리(REQUIRES_NEW)와 같은 결.
      log.warn("CRITIQUE_UPLOAD 유사검색 실패 — 텍스트 비평 폴백: error_class={}", e.getClass().getSimpleName());
      return List.of();
    }
  }

  /** ImageResult → ReferenceImage 어댑터 (SearchExecutor 와 동일 규칙: 1-based index, 표시 필드 복원). */
  private static ReferenceImage toReferenceImage(ImageResult r, int index) {
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
        r.source());
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
