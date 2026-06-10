package com.drawe.backend.domain.llm.session;

import static org.assertj.core.api.Assertions.assertThat;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.ReferenceImage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * SessionService 통합 테스트 — 진짜 Redis 사용.
 *
 * <p>S2' Phase 6 — 단기 메모리 동작 검증. 멀티턴 시나리오를 진짜 Redis 와 상호작용으로 검증.
 *
 * <p>실행 전 조건:
 * <ul>
 *   <li>로컬 Redis 기동 ({@code redis-cli ping} → PONG)</li>
 *   <li>application.properties 의 Redis 설정이 로컬 가리키도록</li>
 * </ul>
 *
 * <p>이 테스트는 MySQL 폴백 시나리오는 검증하지 않음 (Mockito 단위 테스트
 * {@link RedisSessionServiceTest} 에서 검증). 여기선 Redis 와의 진짜 라운드트립 + 멀티턴 흐름
 * 확인이 목적.
 */
@SpringBootTest
class SessionServiceIntegrationTest {

  private static final Long USER_ID = 999L;
  private static final Long PROJECT_ID = 999L;

  @Autowired private SessionService sessionService;
  @Autowired private StringRedisTemplate redisTemplate;

  @BeforeEach
  @AfterEach
  void cleanup() {
    redisTemplate.delete("session:" + USER_ID + ":" + PROJECT_ID);
  }

  // ─────────────────────────────────────────────────────
  // 기본 라운드트립
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("save → getOrRestore — Redis 라운드트립 OK")
  void saveAndRestoreFromRedis() {
    SessionData original =
        SessionData.start(USER_ID, PROJECT_ID)
            .withSearchResult(
                IntentCode.NEW_SEARCH,
                List.of("벚꽃", "수채화"),
                List.of(refImage(123L, 1, "수채화"), refImage(456L, 2, "잉크")));

    sessionService.save(original);

    SessionData restored = sessionService.getOrRestore(USER_ID, PROJECT_ID, null);

    assertThat(restored.userId()).isEqualTo(USER_ID);
    assertThat(restored.projectId()).isEqualTo(PROJECT_ID);
    assertThat(restored.previousReferences()).hasSize(2);
    assertThat(restored.previousReferences().get(0).imageId()).isEqualTo(123L);
    assertThat(restored.previousReferences().get(0).index()).isEqualTo(1);
    assertThat(restored.lastIntent()).isEqualTo(IntentCode.NEW_SEARCH);
    assertThat(restored.lastKeywords()).containsExactly("벚꽃", "수채화");
  }

  // ─────────────────────────────────────────────────────
  // 멀티턴 시나리오 — 실제 사용자 흐름
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("멀티턴: NEW_SEARCH → KEEP → KEEP 흐름")
  void multiTurnSearchThenKeep() {
    // 턴 1: NEW_SEARCH
    SessionData turn1 =
        SessionData.start(USER_ID, PROJECT_ID)
            .withSearchResult(
                IntentCode.NEW_SEARCH,
                List.of("벚꽃"),
                List.of(refImage(123L, 1, "수채화"), refImage(456L, 2, "잉크")));
    sessionService.save(turn1);

    // 턴 2: KEEP — "1번 색감 어떻게?"
    SessionData turn2State = sessionService.getOrRestore(USER_ID, PROJECT_ID, null);
    assertThat(turn2State.previousReferences()).hasSize(2);
    assertThat(turn2State.lastIntent()).isEqualTo(IntentCode.NEW_SEARCH);

    SessionData turn2Updated = turn2State.withKeep(IntentCode.KEEP);
    sessionService.save(turn2Updated);

    // 턴 3: KEEP — "더 자세히"
    SessionData turn3State = sessionService.getOrRestore(USER_ID, PROJECT_ID, null);
    assertThat(turn3State.previousReferences()).hasSize(2); // 유지됨
    assertThat(turn3State.previousReferences().get(0).imageId()).isEqualTo(123L);
    assertThat(turn3State.lastIntent()).isEqualTo(IntentCode.KEEP);
  }

  @Test
  @DisplayName("멀티턴: NEW_SEARCH → KEEP → NEW_SEARCH (새 검색) → references 갱신")
  void multiTurnSearchThenKeepThenNewSearch() {
    // 턴 1: 벚꽃 검색
    SessionData turn1 =
        SessionData.start(USER_ID, PROJECT_ID)
            .withSearchResult(
                IntentCode.NEW_SEARCH, List.of("벚꽃"), List.of(refImage(123L, 1, "수채화")));
    sessionService.save(turn1);

    // 턴 2: KEEP
    SessionData turn2 = sessionService.getOrRestore(USER_ID, PROJECT_ID, null).withKeep(IntentCode.KEEP);
    sessionService.save(turn2);

    // 턴 3: NEW_SEARCH — 단풍으로 전환
    SessionData turn3State = sessionService.getOrRestore(USER_ID, PROJECT_ID, null);
    SessionData turn3Updated =
        turn3State.withSearchResult(
            IntentCode.NEW_SEARCH,
            List.of("단풍"),
            List.of(refImage(789L, 1, "유화"), refImage(101L, 2, "수채화")));
    sessionService.save(turn3Updated);

    // 턴 4 확인: 새 references 로 교체됨
    SessionData turn4State = sessionService.getOrRestore(USER_ID, PROJECT_ID, null);
    assertThat(turn4State.previousReferences()).hasSize(2);
    assertThat(turn4State.previousReferences().get(0).imageId()).isEqualTo(789L); // 단풍
    assertThat(turn4State.lastKeywords()).containsExactly("단풍");
  }

  // ─────────────────────────────────────────────────────
  // 격리 — 프로젝트별 분리
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("프로젝트별 격리 — 다른 projectId 는 다른 세션")
  void projectIsolation() {
    Long projectA = USER_ID * 10 + 1; // 9991
    Long projectB = USER_ID * 10 + 2; // 9992

    try {
      // 프로젝트 A: 벚꽃
      sessionService.save(
          SessionData.start(USER_ID, projectA)
              .withSearchResult(
                  IntentCode.NEW_SEARCH, List.of("벚꽃"), List.of(refImage(123L, 1, "수채화"))));

      // 프로젝트 B: 단풍
      sessionService.save(
          SessionData.start(USER_ID, projectB)
              .withSearchResult(
                  IntentCode.NEW_SEARCH, List.of("단풍"), List.of(refImage(789L, 1, "유화"))));

      // 각각 독립적으로 가져옴
      SessionData a = sessionService.getOrRestore(USER_ID, projectA, null);
      SessionData b = sessionService.getOrRestore(USER_ID, projectB, null);

      assertThat(a.previousReferences().get(0).imageId()).isEqualTo(123L);
      assertThat(a.lastKeywords()).containsExactly("벚꽃");

      assertThat(b.previousReferences().get(0).imageId()).isEqualTo(789L);
      assertThat(b.lastKeywords()).containsExactly("단풍");
    } finally {
      redisTemplate.delete("session:" + USER_ID + ":" + projectA);
      redisTemplate.delete("session:" + USER_ID + ":" + projectB);
    }
  }

  // ─────────────────────────────────────────────────────
  // clear
  // ─────────────────────────────────────────────────────

  @Test
  @DisplayName("clear — Redis 키 삭제 후 다시 가져오면 빈 상태")
  void clearRemovesSession() {
    sessionService.save(
        SessionData.start(USER_ID, PROJECT_ID)
            .withSearchResult(
                IntentCode.NEW_SEARCH, List.of("벚꽃"), List.of(refImage(123L, 1, "수채화"))));

    sessionService.clear(USER_ID, PROJECT_ID);

    SessionData restored = sessionService.getOrRestore(USER_ID, PROJECT_ID, null);
    assertThat(restored.previousReferences()).isEmpty();
    assertThat(restored.lastIntent()).isNull();
  }

  // ─────────────────────────────────────────────────────
  // 헬퍼
  // ─────────────────────────────────────────────────────

  private ReferenceImage refImage(Long imageId, int index, String tag) {
    return new ReferenceImage(
        imageId, index, "https://example.com/" + imageId, "photographer", BigDecimal.valueOf(0.8), List.of(tag));
  }
}
