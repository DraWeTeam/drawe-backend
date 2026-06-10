package com.drawe.backend.domain.llm.search;

import com.drawe.backend.domain.llm.contract.StepContext;
import com.drawe.backend.domain.llm.contract.StepType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ExtractKeywordsExecutor 단위 테스트.
 *
 * <p>본 추출 로직은 {@link KomoranKeywordExtractorTest} 에서 검증.
 * 여기는 wrap 의 책임 — 위임 + StepContext 조작만.
 */
class ExtractKeywordsExecutorTest {

    /**
     * StepContext 빌더 헬퍼 — cleanedMessage 외엔 의미 없으므로 start() 팩토리로 압축.
     * start() 를 쓰면 record 에 필드가 더 늘어도 이 테스트는 안 깨진다.
     */
    private StepContext newCtx(String cleanedMessage) {
        return StepContext.start(
                1L,                  // userId
                1L,                  // projectId
                "session-1",         // sessionId
                null,                // rawMessage
                cleanedMessage,      // cleanedMessage
                null,                // intent
                null,                // uploadedImageUrl
                null                 // previousReferences
        );
    }

    @Test
    @DisplayName("type() = EXTRACT_KEYWORDS")
    void typeIsExtractKeywords() {
        var extractor = mock(KomoranKeywordExtractor.class);
        var sut = new ExtractKeywordsExecutor(extractor);

        assertThat(sut.type()).isEqualTo(StepType.EXTRACT_KEYWORDS);
    }

    @Test
    @DisplayName("execute() — cleanedMessage 위임 + keywords 채움")
    void delegatesToExtractor() {
        var extractor = mock(KomoranKeywordExtractor.class);
        when(extractor.extract(eq("수채화 풍경")))
                .thenReturn(List.of("watercolor", "landscape"));

        var sut = new ExtractKeywordsExecutor(extractor);

        StepContext result = sut.execute(newCtx("수채화 풍경"));

        verify(extractor).extract("수채화 풍경");
        assertThat(result.keywords()).containsExactly("watercolor", "landscape");
        // 다른 필드 보존
        assertThat(result.cleanedMessage()).isEqualTo("수채화 풍경");
        assertThat(result.userId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("execute() — extractor 가 빈 리스트 반환 시 그대로 전달")
    void emptyKeywords() {
        var extractor = mock(KomoranKeywordExtractor.class);
        when(extractor.extract(eq("고마워"))).thenReturn(List.of());

        var sut = new ExtractKeywordsExecutor(extractor);

        StepContext result = sut.execute(newCtx("고마워"));

        assertThat(result.keywords()).isEmpty();
    }
}