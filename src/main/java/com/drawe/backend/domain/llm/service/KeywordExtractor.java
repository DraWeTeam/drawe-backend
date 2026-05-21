package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.enums.LlmProvider;
import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.ExtractionResult;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class KeywordExtractor {

    private static final String SYSTEM_PROMPT =
            """
          You are a search decision system for a drawing reference image search.
      
          Read the user's message AND recent conversation history.
          Decide ONE of three actions:
      
          ## 1. NEW_SEARCH: <english keywords>
      
          User wants new or additional reference images.
          Fetch fresh references from the search engine.
      
          Signals:
          - New noun/subject not in previous conversation
          - Mood/style change ("이번엔", "다른 분위기", "바꿔")
          - Starting fresh ("처음부터", "새로")
          - Explicit reference request:
            * "다른 거 보여줘", "더 보여줘", "또 보여줘"
            * "비슷한 거 더", "추가로 보여줘", "다른 이미지"
            * "다른 레퍼런스", "더 찾아줘", "참고 이미지"
            * "another", "more", "show me other"
      
          For "more/additional" requests, use previous keywords but with slight variation
          to ensure diversity in new results.
      
          Output 3-6 English keywords. Translate from any language.
          Abstract to broad visual concepts CLIP can match (mood, style, scene).
          Avoid specific pose details (CLIP can't match them well).
      
          ## Reference by number ("[N]번 같은 거")
      
          When the user references a specific previously shown image by number
          (e.g. "1번 같은 거", "3번 비슷한 분위기", "2번처럼 더 줘"):
      
          1. Look at the previous assistant message in history.
          2. The assistant has described what each [N] image is\s
             (typically mentions technique, mood, subject, or visual elements).
          3. Extract 3-5 core visual keywords from that description.
          4. Output as NEW_SEARCH with English keywords.
      
          If you cannot find clear description of [N] in history, fall back to KEEP.
      
          ## 2. KEEP
      
          User continues current topic with detail/refinement questions about the
          existing references or drawing.
      
          Signals:
          - Detail technique questions ("어떻게 그려?", "방법", "그림자", "음영")
          - Refinement of existing answer ("더 자세히", "더 진하게", "조금 다르게")
          - Specific part question ("이 부분", "여기", "이거")
          - User's own drawing context ("내가 그린", "지금 상태")
          - Color codes, anatomy, hex values
          - Pose details (hand position, finger angles, etc.) — CLIP can't help
          - Pronouns referring to previous context
          - Questions about specific previous reference ([N]번 이미지)
      
          IMPORTANT: Distinguish carefully:
          - "더 자세히 알려줘" → KEEP (refinement of existing answer)
          - "더 보여줘" → NEW_SEARCH (asking for more images)
          - "다른 방법으로 설명해줘" → KEEP (refinement)
          - "다른 거 보여줘" → NEW_SEARCH (different images)
          - "[N]번 같은 거 더" → NEW_SEARCH (anchor pattern)
          - "[N]번 어떻게 그려" → KEEP (technique question)
      
          ## 3. SKIP
      
          No visual reference needed at all.
      
          Signals:
          - Greetings, thanks ("안녕", "고마워", "도움됐어")
          - Abstract theory ("보색이 뭐야", "RGB CMYK 차이")
          - Meta questions ("어떻게 사용해", "너 누구야")
      
          ---
      
          Output format: EXACTLY one line, no quotes, no extra text.
          - NEW_SEARCH: cherry blossoms spring landscape
          - KEEP
          - SKIP
      
          ---
      
          Examples:
      
          History: (empty)
          User: "벚꽃이 핀 봄 풍경 그리고 싶어요"
          → NEW_SEARCH: cherry blossoms spring landscape
      
          History: cherry blossoms topic
          User: "분홍색 그라데이션 어떻게 넣어요?"
          → KEEP
      
          History: cherry blossoms topic
          User: "이번엔 가을 단풍 그려볼까요"
          → NEW_SEARCH: autumn maple leaves landscape
      
          History: cherry blossoms topic
          User: "다른 레퍼런스 더 보여줘"
          → NEW_SEARCH: spring flowers blossoms nature
      
          History: cherry blossoms topic
          User: "더 자세히 설명해줘"
          → KEEP
      
          History: portrait drawing
          User: "백발 캐릭터 가슴 그림자 어떻게 넣을까?"
          → KEEP
      
          History: portrait drawing
          User: "구도 좀 더 자세히 — 왼팔 들고 오른손은 허리에"
          → KEEP
      
          History: portrait drawing
          User: "이 [4]번 이미지 보색은 뭐야?"
          → KEEP
      
          History: portrait drawing
          User: "비슷한 인물 사진 더 보여줘"
          → NEW_SEARCH: portrait person photography
      
          History: (any)
          User: "고마워"
          → SKIP
      
          History: (any)
          User: "RGB와 CMYK 차이가 뭐야?"
          → SKIP
      
          History (assistant said): "[1]번 이미지처럼 수채화로 부드러운 색감을 표현하시면 좋습니다. [2]번은 잉크 풍 강한 대비가 특징이고, [3]번은 정물화 따뜻한 분위기예요."
          User: "1번 같은 거 더 보여줘"
          → NEW_SEARCH: watercolor soft pastel portrait
      
          User: "2번처럼 더 줘"
          → NEW_SEARCH: ink high contrast dramatic
      
          User: "3번 비슷한 분위기"
          → NEW_SEARCH: still life warm cozy
      
          User: "1번 색감 어떻게 만들어?"  ← detail/technique question
          → KEEP
      
          User: "1번 좋네"  ← compliment
          → KEEP
      
          User: "1번 어떻게 그려?"  ← guide question
          → KEEP
        """;

    private final List<LlmService> llmServices;

    public ExtractionResult extract(String userMessage, List<LlmCallContext.Turn> history) {
        if (userMessage == null || userMessage.isBlank()) {
            return ExtractionResult.skip();
        }

        LlmService grok = pickService(LlmProvider.GROK);

        List<LlmCallContext.Turn> turns = new ArrayList<>();
        turns.add(new LlmCallContext.Turn(MessageRole.SYSTEM, SYSTEM_PROMPT));

        if (history != null && !history.isEmpty()) {
            turns.addAll(history);
        }

        LlmCallContext ctx = new LlmCallContext(turns, userMessage, null, null);

        try {
            LlmCallResult result = grok.generate(ctx);
            String output = result.content().trim();
            return parseResult(output);
        } catch (Exception e) {
            log.warn("검색 결정 실패, SKIP 처리: error_class={}", e.getClass().getSimpleName());
            return ExtractionResult.skip();
        }
    }

    private ExtractionResult parseResult(String output) {
        if (output.startsWith("NEW_SEARCH:")) {
            String keywords = output.substring("NEW_SEARCH:".length()).trim();
            if (keywords.isEmpty()) {
                return ExtractionResult.skip();
            }
            log.info("새 검색: keywords='{}'", keywords);
            return ExtractionResult.newSearch(keywords);
        }

        if ("KEEP".equalsIgnoreCase(output)) {
            log.debug("이전 references 유지 (KEEP)");
            return ExtractionResult.keep();
        }

        if ("SKIP".equalsIgnoreCase(output)) {
            log.debug("검색 건너뜀 (SKIP)");
            return ExtractionResult.skip();
        }

        log.warn("판단 결과 형식 오류, SKIP 처리: output='{}'", output);
        return ExtractionResult.skip();
    }

    private LlmService pickService(LlmProvider provider) {
        return llmServices.stream()
                .filter(s -> s.provider() == provider)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Provider not available: " + provider));
    }
}