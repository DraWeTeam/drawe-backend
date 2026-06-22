package com.drawe.backend.domain.llm.context;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.session.SessionData;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 멀티턴 컨텍스트 조립 시나리오 — 세션(상태) + 컨텍스트 레이어(정제·trim)를 함께 검증.
 *
 * <p>실제 라이브 통합(ChatLlmService / A 의 ComposeExecutor)은 공동 작업이라 아직 미배선이다. 본 테스트는 그와 별개로 <b>B 컴포넌트만
 * 묶어</b> "턴을 넘어 LLM 에 갈 컨텍스트가 올바른지"를 솔로로 검증한다 — A 코드·S1 키워드 작업과 무관.
 *
 * <p>검증 시나리오:
 *
 * <ol>
 *   <li>턴1 NEW_SEARCH → 세션에 검색결과(previousReferences) 저장
 *   <li>턴2 KEEP → references 유지 + 직전결과를 SYSTEM 으로 주입 + 히스토리 [N] 정제 + 예산 trim
 * </ol>
 */
class MultiTurnContextAssemblyTest {

  private static final Long USER_ID = 1L;
  private static final Long PROJECT_ID = 2L;

  private TokenCounter tokenCounter;
  private TokenBudgetConfig budget;
  private TokenAwareHistoryTrimmer trimmer;

  @BeforeEach
  void setUp() {
    tokenCounter = new TokenCounter();
    HistorySanitizer sanitizer = new HistorySanitizer();
    TopicChangeDetector topicDetector = new NoopTopicChangeDetector();
    budget = new TokenBudgetConfig(); // 기본 1200 / 4000 / 1500 / 8000
    trimmer =
        new TokenAwareHistoryTrimmer(
            tokenCounter, sanitizer, topicDetector, budget, new SimpleMeterRegistry());
    trimmer.init();
  }

  // ───────── 헬퍼 ─────────

  private ReferenceImage ref(long id, int index, String tag) {
    return new ReferenceImage(
        id, index, "https://img/" + id, "photographer", BigDecimal.valueOf(0.8), List.of(tag));
  }

  /** 직전 검색결과를 SYSTEM 블록 문자열로 — 통합 시 ComposeExecutor 가 할 주입을 모사. */
  private String referenceBlock(List<ReferenceImage> refs) {
    StringBuilder sb = new StringBuilder("[참고 이미지]\n");
    for (ReferenceImage r : refs) {
      sb.append("[")
          .append(r.index())
          .append("] ")
          .append(String.join(", ", r.tags()))
          .append("\n");
    }
    return sb.toString();
  }

  private LlmCallContext.Turn turn(MessageRole role, String content) {
    return new LlmCallContext.Turn(role, content);
  }

  // ───────── 시나리오 ─────────

  @Test
  @DisplayName("멀티턴 KEEP — 직전 references 유지 + SYSTEM 주입 생존 + 히스토리 [N] 정제 + 예산 내")
  void keepTurnMaintainsReferencesAndSanitizesHistory() {
    // 턴1: NEW_SEARCH — "벚꽃 수채화 찾아줘" → 2건
    SessionData turn1 =
        SessionData.start(USER_ID, PROJECT_ID)
            .withSearchResult(
                IntentCode.NEW_SEARCH,
                List.of("벚꽃", "수채화"),
                List.of(ref(101L, 1, "벚꽃"), ref(102L, 2, "수채화")));

    // 턴2: KEEP — "1번 색감 더 자세히"
    SessionData turn2 = turn1.withKeep(IntentCode.KEEP);
    // 멀티턴 핵심: KEEP 이 references 를 유지하는가
    assertThat(turn2.previousReferences()).hasSize(2);

    // 컨텍스트 조립: SYSTEM(직전결과 주입) + 이전 대화([N] 마커 포함) + 현재 질문
    List<LlmCallContext.Turn> history =
        List.of(
            turn(MessageRole.SYSTEM, referenceBlock(turn2.previousReferences())),
            turn(MessageRole.USER, "벚꽃 수채화 찾아줘"),
            turn(MessageRole.ASSISTANT, "[1]번 그림처럼 부드러운 벚꽃 색감이 좋아요"),
            turn(MessageRole.USER, "1번 색감 더 자세히"));

    List<LlmCallContext.Turn> assembled = trimmer.trim(history, "1번 색감 더 자세히");

    // 1) 직전 검색결과(SYSTEM)가 컨텍스트에 생존 — KEEP 멀티턴 페이로드 유지
    assertThat(assembled)
        .anyMatch(t -> t.role() == MessageRole.SYSTEM && t.content().contains("벚꽃"));

    // 2) ASSISTANT 히스토리의 옛 [N] 마커 제거 — 옛/새 refs 혼선 차단
    assertThat(assembled)
        .filteredOn(t -> t.role() == MessageRole.ASSISTANT)
        .isNotEmpty()
        .allSatisfy(t -> assertThat(t.content()).doesNotContain("[1]"));

    // 3) 전체가 토큰 예산(system + history) 안
    int total = tokenCounter.countTurns(assembled);
    assertThat(total).isLessThanOrEqualTo(budget.getSystemBudget() + budget.getHistoryBudget());
  }

  @Test
  @DisplayName("토큰 예산 초과 — 오래된 히스토리 turn 이 history 예산 내로 trim")
  void historyTrimmedToBudget() {
    List<LlmCallContext.Turn> history = new ArrayList<>();
    history.add(turn(MessageRole.SYSTEM, "페르소나 시스템 프롬프트"));
    String filler = "벚꽃 수채화 색감 구도 명암 ".repeat(60); // turn 당 토큰 크게
    for (int i = 0; i < 40; i++) {
      history.add(turn(MessageRole.USER, "질문 " + i + " " + filler));
      history.add(turn(MessageRole.ASSISTANT, "답변 " + i + " " + filler));
    }

    List<LlmCallContext.Turn> trimmed = trimmer.trim(history, "현재 질문");

    // 히스토리(non-system) 토큰이 예산 내
    int historyTokens =
        tokenCounter.countTurns(
            trimmed.stream().filter(t -> t.role() != MessageRole.SYSTEM).toList());
    assertThat(historyTokens).isLessThanOrEqualTo(budget.getHistoryBudget());
    // 오래된 턴이 드롭됨
    assertThat(trimmed.size()).isLessThan(history.size());
  }

  @Test
  @DisplayName("멀티턴 NEW_SEARCH — 새 검색이 직전 references 를 교체 (조립도 새 결과 반영)")
  void newSearchReplacesReferencesInContext() {
    SessionData turn1 =
        SessionData.start(USER_ID, PROJECT_ID)
            .withSearchResult(IntentCode.NEW_SEARCH, List.of("벚꽃"), List.of(ref(101L, 1, "벚꽃")));

    // 턴2: 단풍으로 새 검색 → references 교체
    SessionData turn2 =
        turn1.withSearchResult(IntentCode.NEW_SEARCH, List.of("단풍"), List.of(ref(201L, 1, "단풍")));

    assertThat(turn2.previousReferences()).hasSize(1);
    assertThat(turn2.previousReferences().get(0).imageId()).isEqualTo(201L);

    // 조립된 SYSTEM 블록이 새 결과(단풍) 반영, 옛 결과(벚꽃) 없음
    String block = referenceBlock(turn2.previousReferences());
    assertThat(block).contains("단풍").doesNotContain("벚꽃");
  }
}
