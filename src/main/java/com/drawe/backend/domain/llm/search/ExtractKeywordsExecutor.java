package com.drawe.backend.domain.llm.search;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepExecutor;
import com.drawe.backend.domain.llm.contract.StepType;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * EXTRACT_KEYWORDS 단계 실행기 — A·B 계약 wrap.
 *
 * <p>내부는 {@link KomoranKeywordExtractor} (4단계 완성) 에 단순 위임. StepContext.cleanedMessage 를 읽고 keywords
 * 슬롯을 채움.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractKeywordsExecutor implements StepExecutor {

  private final KomoranKeywordExtractor extractor;

  @Override
  public StepType type() {
    return StepType.EXTRACT_KEYWORDS;
  }

  @Override
  public StepContext execute(StepContext ctx) {
    List<String> keywords = extractor.extract(ctx.cleanedMessage());

    if (log.isDebugEnabled()) {
      log.debug("EXTRACT_KEYWORDS: '{}' → {}", ctx.cleanedMessage(), keywords);
    }

    // @With 가 자동 생성한 wither
    return ctx.withKeywords(keywords);
  }
}
