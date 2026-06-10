# SCRUM-84 — S1' 트랙 A 로컬 실연동 검증 결과

**테스트 일시:** 2026-06-10 10:35~10:37 KST
**대상 브랜치:** `feature/SCRUM-84-AI-pipeline-A`
**테스트 방식:** 로컬 앱 실행(`gradlew bootRun`) + 실제 HTTP 호출. 외부 의존성(MySQL·Redis·FastAPI·Pinecone·Grok) **전부 실연동**. 메트릭은 `/actuator/prometheus`, shadow 동작은 앱 로그(`🔬`)로 확인.

## 테스트 환경

| 항목 | 값 |
|------|-----|
| 앱 포트 | 8081 |
| DB / Redis | docker-compose (`drawe-drawe_db-1` MySQL 8.4 @3309, `drawe-drawe_redis-1` Valkey @6379) |
| 테스트 user | id=1, email=test1@test.com |
| 테스트 project | id=2 (user_id=1 소유) |
| 인증 | `JWT_SECRET`(BASE64 디코딩) 기반 HS256 토큰 직접 발급 (sub=1) |
| 검색 모델 | `grok-4-fast-non-reasoning` (Grok 키워드 추출) + FastAPI embed + Pinecone |

## 검증 대상

| # | 항목 | 커밋 상태 |
|---|------|----------|
| ① | SearchService 트랜잭션 전파 버그 수정 (REQUIRES_NEW) | **미커밋** |
| ② | WorkflowService shadow 연결 (NEW_SEARCH) | 커밋됨 (146e3e0) |
| ③ | RulePreRouter 룰 프리라우터 (LLM 콜 0) | 커밋됨 |

---

## ① 트랜잭션 버그 수정 — 검증 완료 ✅

**시나리오:** 백엔드를 `FASTAPI_URL=http://localhost:9999`(죽은 포트)로 재기동 → embed 호출이 connection refused → NEW_SEARCH chat 호출.

**핵심 질문:** embed 장애 시 chat()이 500(`UnexpectedRollbackException`) 대신 정상 응답(빈 검색결과)을 내는가?

요청:
```json
POST /projects/2/chat
Authorization: Bearer <jwt>
{ "message": "역동적인 고양이 포즈 찾아줘" }
```

응답 (**HTTP 200**, 2.24초):
```json
{
  "success": true,
  "data": {
    "sessionId": "39b05a3a-ad90-4f1d-8e77-618568e8acd0",
    "type": "guide",
    "message": "자료가 좀 부족한 것 같아요. AI 이미지로 생성해드릴까요?",
    "references": [],
    "referencesAction": "NEW_SEARCH",
    "offerGenerate": true,
    "suggestedPrompt": "역동적인 고양이 포즈 찾아줘"
  }
}
```

앱 로그 (증거 체인):
```
ERROR 검색 실패: keywords_length=33, error_class=WebClientRequestException
INFO  📊 event_type=search_blocked ... blocked=true, error_code=SEARCH_FAILED
(이후 llm_messages INSERT — chat() 정상 커밋)
```

| 구분 | 내용 |
|------|------|
| **기대값** | embed 장애 시 chat()이 HTTP 200 + 빈 references + offerGenerate=true. `UnexpectedRollbackException` 미발생 |
| **결과값** | HTTP 200, references=[], offerGenerate=true. 로그에 graceful 처리(`search_blocked`) 후 정상 커밋. **`UnexpectedRollbackException` 흔적 0** |
| **차이** | 없음 |

→ `@Transactional(propagation=REQUIRES_NEW)`가 검색 실패를 호출자(chat) 트랜잭션에서 격리함이 실증됨. `WebClientRequestException`(connection refused)이 SearchService의 별도 트랜잭션만 rollback → 호출자 트랜잭션은 깨끗 → catch가 삼킨 뒤 chat()이 정상 커밋.

---

## ② shadow 메트릭 — 검증 완료 ✅

**시나리오:** FastAPI 정상 상태에서 NEW_SEARCH chat 호출 1회. shadow(WorkflowService)가 병렬로 한 번 더 돌며 Komoran vs Grok 검색결과를 비교.

실응답 (HTTP 200, 정상): Grok 키워드 검색 10건 + LLM 답변 `"[2]번처럼 playful한 고양이 포즈가 벚꽃 배경에..."`

shadow 로그:
```
🔬 shadow workflow: code=005 outcome=miss base_n=10 shadow_n=0 overlap=0
```

prometheus:
```
drawe_workflow_shadow_total{outcome="miss"} 1.0
```

| 구분 | 내용 |
|------|------|
| **기대값** | shadow가 NEW_SEARCH에서 작동하고 `drawe.workflow.shadow` 메트릭 기록. 실응답 영향 0 |
| **결과값** | shadow 작동, outcome=miss 기록. 실응답은 HTTP 200 정상 (shadow가 실응답 안 깸) |
| **차이** | 없음 (동작 검증 목적 달성) |

**관찰:** `base_n=10`(Grok) vs `shadow_n=0`(Komoran) → overlap=0 → `miss`. Komoran 경로가 검색결과를 **아예 못 냄**. shadow의 목적(승격 전 사전 품질 검증)대로 "Komoran≠Grok" 신호를 실데이터로 포착. → 아래 "후속 조사 거리" 참조.

---

## ③ RulePreRouter — 검증 완료 ✅

**시나리오:** 세 가지 입력으로 룰 분기 자극.

| 입력 | 분기 | 응답 | LLM 검색 콜 |
|------|------|------|------------|
| `고마워` | SKIP | `referencesAction":"SKIP"` (HTTP 200, 0.96초) | 0 (룰) |
| `고양이 그려줘` | GENERATE_NOW | Bria 생성 시도 → 503* | 0 (룰) |
| `역동적인 고양이 포즈 찾아줘` | NEW_SEARCH | 검색 10건 + LLM 답변 (HTTP 200, 2.46초) | Grok 폴백 |

prometheus:
```
drawe_intent_route_total{outcome="rule_hit"} 6.0
drawe_intent_route_total{outcome="rule_miss"} 1.0
drawe_intent_rule_total{rule_id="thanks_greeting",action="SKIP"} 5.0
drawe_intent_rule_total{rule_id="generate_verb",action="GENERATE_NOW"} 1.0
drawe_intent_classify_seconds_count{outcome="success"} 1.0
drawe_llm_call_seconds_count{provider="GROK",outcome="success"} 6.0
```

| 구분 | 내용 |
|------|------|
| **기대값** | 인사·감사 → SKIP, 명시적 생성 동사 → GENERATE_NOW를 LLM 콜 0으로. 검색문은 rule_miss(Grok 폴백). 적중률 ≥30% (ADR DoD) |
| **결과값** | rule_hit=6, rule_miss=1 → **적중률 85.7%**. thanks_greeting·generate_verb 룰 정상 매칭 |
| **차이** | 없음. DoD 큰 폭 통과 |

\* `고양이 그려줘`의 503은 **룰 분기와 무관** — GENERATE_NOW로 정확히 라우팅됐고, 그 뒤 Bria 이미지 생성 외부 호출이 실패(키 미설정/외부 요인 추정). RulePreRouter 검증에는 영향 없음.

---

## 후속 조사 거리 (이번 검증 범위 밖)

1. **shadow_n=0** — Komoran(EXTRACT_KEYWORDS) 경로가 NEW_SEARCH 입력에서 검색결과를 0건 냄(Grok은 10건). shadow→실연결 승격을 막는 실데이터 신호. Komoran 키워드 추출이 빈값을 내는지 / SEARCH executor 연결 문제인지 별도 확인 필요.
2. **`고양이 그려줘` → 503 AI_SERVICE_ERROR** — GENERATE_NOW 분기는 정상이나 Bria 생성 실패. 키 설정/외부 연동 점검.

## 결론

S1' 트랙 A의 세 항목(① 트랜잭션 버그 수정 · ② shadow 연결 · ③ RulePreRouter) 모두 로컬 실연동에서 의도대로 동작함을 확인.

- **① 트랜잭션 버그 수정은 검증 완료 → 커밋 권장** (현재 미커밋)
- ②③은 이미 커밋된 상태로 메트릭·로그 동작 확인

**다음 단계:**
- SearchService(REQUIRES_NEW) 커밋
- shadow_n=0 원인 조사 (Komoran executor) — 승격 판단의 선결 조건
