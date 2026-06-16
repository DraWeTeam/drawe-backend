package com.drawe.backend.domain.llm.contract;

import java.util.List;

/**
 * SEARCH 단계의 점수 통계·차단 판정 (live 갭 닫기, S2' 트랙 A).
 *
 * <p>{@code SearchExecutor} 가 점수 가드(avg&lt;0.2 || max&lt;0.21 → 무관 결과 차단)를 적용하고 그 판정·통계를
 * 이 record 에 실어 {@link StepContext} 로 운반한다. analytics 발사(SEARCH_EXECUTED/SEARCH_BLOCKED)는
 * Executor 가 아니라 {@code ChatLlmService.chatViaWorkflow} 가 이 통계를 보고 한다 —
 * Executor 를 순수하게 유지하고(부수효과 분리), shadow 경로(같은 Executor 를 타지만 analytics 코드가 없는
 * 경로)가 analytics 를 중복 발사하지 않게 하기 위함이다.
 *
 * <p>{@code blocked=true} 면 {@link StepContext#references()} 는 비어 있다(차단됨). 차단 사유는
 * {@code blockedReason}({@code low_score}). 점수 통계(avg/max/min)·imageIds·scores 는 analytics payload 로
 * 그대로 흘려보낸다(레거시 {@code handleSearchDecision} 와 동등).
 *
 * @param keyword       검색에 쓰인 query (키워드 space-join).
 * @param resultCount   검색이 돌려준 원본 결과 수 (차단 전).
 * @param avgScore      점수 평균 (결과 0 이면 0.0).
 * @param maxScore      점수 최대 (결과 0 이면 0.0).
 * @param minScore      점수 최소 (결과 0 이면 0.0).
 * @param blocked       점수 가드 또는 검색 예외로 차단됐는지.
 * @param blockedReason 차단 사유 ({@code low_score} | {@code exception}). 차단 아니면 null.
 * @param imageIds      결과 image id 목록 (analytics·디버깅용).
 * @param scores        결과 점수 목록 (소수점 3자리 반올림).
 * @param errorClass    검색 예외로 차단된 경우({@code blockedReason=exception}) 예외 클래스명. 그 외 null.
 *                      레거시 {@code handleSearchDecision} 의 catch 가 SEARCH_BLOCKED payload 에 담던
 *                      {@code error_class} 와 동등.
 */
public record SearchStats(
    String keyword,
    int resultCount,
    double avgScore,
    double maxScore,
    double minScore,
    boolean blocked,
    String blockedReason,
    List<Long> imageIds,
    List<Double> scores,
    String errorClass) {

  public SearchStats {
    imageIds = imageIds == null ? List.of() : List.copyOf(imageIds);
    scores = scores == null ? List.of() : List.copyOf(scores);
  }
}
