package com.drawe.backend.domain.llm.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link SessionData} 상태 전이·불변 정책 테스트.
 *
 * <p>세션 정책의 핵심 ("KEEP 시 references 유지, NEW_SEARCH 시 교체") 은 이 record 의 전이 메서드에 담겨 있다. {@link
 * RedisSessionServiceTest} 는 저장/복원 인프라를, 본 테스트는 정책 자체를 검증한다.
 */
class SessionDataTest {

  private static ReferenceImage ref(long id, int index) {
    return new ReferenceImage(
        id, index, "url" + id, "photographer", BigDecimal.valueOf(0.9), List.of("수채화"));
  }

  // ─────────────────────────────────────────────────────
  // 생성자 null 방어
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("생성자 — null references/keywords 는 빈 리스트로 정규화")
  void constructorNullDefenses() {
    SessionData data = new SessionData(1L, 2L, null, null, null, null);

    assertThat(data.previousReferences()).isNotNull().isEmpty();
    assertThat(data.lastKeywords()).isNotNull().isEmpty();
    assertThat(data.lastIntent()).isNull();
    assertThat(data.lastUpdatedAt()).isNotNull(); // null → Instant.now()
  }

  @Test
  @DisplayName("start — 빈 세션 시작 (references/keywords 비어 있음, intent null)")
  void startCreatesEmptySession() {
    SessionData data = SessionData.start(42L, 7L);

    assertThat(data.userId()).isEqualTo(42L);
    assertThat(data.projectId()).isEqualTo(7L);
    assertThat(data.previousReferences()).isEmpty();
    assertThat(data.lastKeywords()).isEmpty();
    assertThat(data.lastIntent()).isNull();
    assertThat(data.lastUpdatedAt()).isNotNull();
  }

  // ─────────────────────────────────────────────────────
  // 상태 전이 — 정책의 핵심
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("withSearchResult — NEW_SEARCH 는 references·keywords·intent 전부 교체")
  void withSearchResultReplacesEverything() {
    SessionData before =
        new SessionData(
            1L,
            2L,
            List.of(ref(100L, 1)),
            IntentCode.KEEP,
            List.of("old"),
            java.time.Instant.now().minusSeconds(60));

    List<ReferenceImage> fresh = List.of(ref(200L, 1), ref(201L, 2));
    SessionData after =
        before.withSearchResult(IntentCode.NEW_SEARCH, List.of("watercolor", "cherry"), fresh);

    // 식별자 보존
    assertThat(after.userId()).isEqualTo(1L);
    assertThat(after.projectId()).isEqualTo(2L);

    // references 완전 교체
    assertThat(after.previousReferences()).hasSize(2);
    assertThat(after.previousReferences().get(0).imageId()).isEqualTo(200L);

    // intent·keywords 교체
    assertThat(after.lastIntent()).isEqualTo(IntentCode.NEW_SEARCH);
    assertThat(after.lastKeywords()).containsExactly("watercolor", "cherry");

    // 시각 갱신
    assertThat(after.lastUpdatedAt()).isAfter(before.lastUpdatedAt());
  }

  @Test
  @DisplayName("withKeep — KEEP 은 references·keywords 유지, intent 만 갱신")
  void withKeepPreservesReferences() {
    List<ReferenceImage> kept = List.of(ref(100L, 1), ref(101L, 2));
    SessionData before =
        new SessionData(
            1L,
            2L,
            kept,
            IntentCode.NEW_SEARCH,
            List.of("watercolor"),
            java.time.Instant.now().minusSeconds(60));

    SessionData after = before.withKeep(IntentCode.KEEP);

    // references 그대로 유지 (멀티턴 핵심)
    assertThat(after.previousReferences()).isEqualTo(kept);
    // keywords 도 유지
    assertThat(after.lastKeywords()).containsExactly("watercolor");
    // intent 만 갱신
    assertThat(after.lastIntent()).isEqualTo(IntentCode.KEEP);
    // 시각 갱신
    assertThat(after.lastUpdatedAt()).isAfter(before.lastUpdatedAt());
  }

  @Test
  @DisplayName("withIntent — SKIP/GENERATE 등은 references 유지, intent·시각만 갱신")
  void withIntentPreservesReferences() {
    List<ReferenceImage> kept = List.of(ref(100L, 1));
    SessionData before =
        new SessionData(
            1L,
            2L,
            kept,
            IntentCode.NEW_SEARCH,
            List.of("watercolor"),
            java.time.Instant.now().minusSeconds(60));

    SessionData after = before.withIntent(IntentCode.GENERATE);

    assertThat(after.previousReferences()).isEqualTo(kept);
    assertThat(after.lastKeywords()).containsExactly("watercolor");
    assertThat(after.lastIntent()).isEqualTo(IntentCode.GENERATE);
    assertThat(after.lastUpdatedAt()).isAfter(before.lastUpdatedAt());
  }

  @Test
  @DisplayName("전이는 원본을 변경하지 않는다 (record 불변성)")
  void transitionsAreImmutable() {
    SessionData original =
        new SessionData(
            1L,
            2L,
            List.of(ref(100L, 1)),
            IntentCode.NEW_SEARCH,
            List.of("a"),
            java.time.Instant.now());

    original.withKeep(IntentCode.KEEP);
    original.withSearchResult(IntentCode.NEW_SEARCH, List.of("b"), List.of(ref(200L, 1)));

    // 원본 불변
    assertThat(original.lastIntent()).isEqualTo(IntentCode.NEW_SEARCH);
    assertThat(original.previousReferences()).hasSize(1);
    assertThat(original.previousReferences().get(0).imageId()).isEqualTo(100L);
    assertThat(original.lastKeywords()).containsExactly("a");
  }
}
