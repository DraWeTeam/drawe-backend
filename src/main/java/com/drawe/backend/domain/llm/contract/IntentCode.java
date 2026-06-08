package com.drawe.backend.domain.llm.contract;

/**
 * 채팅 의도 분류 코드.
 *
 * <p>13개 의도 — 외부 리뷰가 짚은 누락 카테고리 (000, 010~013) 포함.
 *
 * <p>설계 결정:
 * <ul>
 *   <li>009 (N번 참조) <strong>삭제</strong> — 앵커는 의도가 아니라 파라미터.
 *       {@link IntentResult#referencedImages()} 슬롯으로 분리.</li>
 *   <li>기존 {@code ExtractionResult.Action} 과의 매핑은 마이그레이션 가이드 참조.</li>
 * </ul>
 *
 * <p>{@code code} 는 룰 매처 / 메트릭 태그 / 로깅에서 사용.
 */
public enum IntentCode {

    /** 도메인 외 질문 (신규) — 미술과 무관한 잡담·일반 질문. */
    OUT_OF_DOMAIN("000"),

    /** 구도 관련 조언. */
    COMPOSITION("001"),

    /** 빛 / 명암 조언. */
    LIGHTING("002"),

    /** 색감 조언. */
    COLOR("003"),

    /** 기법 조언. */
    TECHNIQUE("004"),

    /** 새 검색 — Komoran + 사전 + 폴백 → CLIP 검색. */
    NEW_SEARCH("005"),

    /** 직전 결과 유지 (이어서 얘기). */
    KEEP("006"),

    /** 검색 스킵 (잡담·인사 등 대답만 필요). */
    SKIP("007"),

    /** 이미지 생성 (Bria). */
    GENERATE("008"),

    // 009 삭제 — 앵커 [N]번 참조는 IntentResult.referencedImages 슬롯으로

    /** 본인 작업물 비평 (신규) — 사용자 업로드 이미지 분석. */
    SELF_CRITIQUE("010"),

    /** 학습 경로 (신규) — "어디서부터 시작해야 해?" 같은 메타 질문. */
    LEARNING_PATH("011"),

    /** 부연 질문 (신규) — "그게 무슨 뜻이야?" 같은 후속. */
    FOLLOWUP("012"),

    /** 비교 (신규) — "수채화 vs 유화 차이가 뭐야?". */
    COMPARE("013");

    private final String code;

    IntentCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
