package com.drawe.backend.domain.llm.contract;

import java.util.List;
import java.util.Map;

import static com.drawe.backend.domain.llm.contract.StepType.COMPOSE;
import static com.drawe.backend.domain.llm.contract.StepType.CRITIQUE_UPLOAD;
import static com.drawe.backend.domain.llm.contract.StepType.EXTRACT_KEYWORDS;
import static com.drawe.backend.domain.llm.contract.StepType.GENERATE_IMAGE;
import static com.drawe.backend.domain.llm.contract.StepType.SEARCH;
import static com.drawe.backend.domain.llm.contract.StepType.TRANSLATE;

/**
 * 의도 → step 시퀀스 정적 라우팅.
 *
 * <p>설계 결정: 동적 {@code PlanSpec} 인터프리터 대신 정적 Map 채택.
 * 10개 의도 중 8개가 정적 시퀀스라 런타임 합성 불필요 (외부 리뷰 결론, ADR §3 참조).
 *
 * <p>011~013 ({@code LEARNING_PATH}, {@code FOLLOWUP}, {@code COMPARE}) 는
 * 베타 후 빈도 확인되면 추가.
 *
 * <p>{@code WorkflowService} 가 의도 분류 결과로 lookup → step 순차 실행.
 */
public final class IntentRouting {

    private IntentRouting() {
        // util class
    }

    /**
     * 의도 → step 시퀀스.
     *
     * <p>키 누락 시 안전 기본값: {@code List.of(COMPOSE)} (단순 답변 생성).
     */
    public static final Map<IntentCode, List<StepType>> ROUTING = Map.ofEntries(
            // 도메인 외·일반 조언 — 단순 답변 생성만
            Map.entry(IntentCode.OUT_OF_DOMAIN, List.of(COMPOSE)),
            Map.entry(IntentCode.COMPOSITION,   List.of(COMPOSE)),
            Map.entry(IntentCode.LIGHTING,      List.of(COMPOSE)),
            Map.entry(IntentCode.COLOR,         List.of(COMPOSE)),
            Map.entry(IntentCode.TECHNIQUE,     List.of(COMPOSE)),

            // 검색 흐름
            Map.entry(IntentCode.NEW_SEARCH,    List.of(EXTRACT_KEYWORDS, SEARCH, COMPOSE)),
            Map.entry(IntentCode.KEEP,          List.of(COMPOSE)),
            Map.entry(IntentCode.SKIP,          List.of(COMPOSE)),

            // 생성 흐름
            Map.entry(IntentCode.GENERATE,      List.of(TRANSLATE, GENERATE_IMAGE)),

            // 비평 흐름
            Map.entry(IntentCode.SELF_CRITIQUE, List.of(CRITIQUE_UPLOAD, COMPOSE))

            // 011~013 은 베타 후 빈도 확인 후 추가
    );
}
