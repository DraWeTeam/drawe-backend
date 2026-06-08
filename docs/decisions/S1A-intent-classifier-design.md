# S1' 트랙 A ② — 경량 의도 분류기 + IntentResult 어댑터 설계

> 작성일: 2026-06-08
> 짝 문서: [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) §4(하이브리드 분류)·§5(IntentCode), [`S1A-rule-prerouter-design.md`](./S1A-rule-prerouter-design.md)(①)
> 성격: 구현 설계 노트.

---

## 0. 목표

트랙 A 의 마지막 조각. 두 가지를 만든다:

1. **IntentResult 어댑터**: ①룰(`RulePreRouter`)·`KeywordExtractor`(Grok) 의 결과(`ExtractionResult` 4 Action)를 contract `IntentResult(code, tier)` 로 변환. WorkflowService 가 소비하는 타입.
2. **미술 의도 분류**: 지금 `KEEP` 으로 뭉뚱그려지는 "기존 레퍼런스에 대한 세부 질문"을 Grok 으로 **001 구도 / 002 빛 / 003 색 / 004 기법** 으로 세분화 (ADR §4 "LLM 이 잡는 것").

**비목표**: 010~013(SELF_CRITIQUE/LEARNING_PATH/FOLLOWUP/COMPARE), 000(OUT_OF_DOMAIN) — 베타 빈도 확인 후 S3'. 복합 의도 — 단일 의도 가정 유지.

---

## 1. Action → IntentCode 매핑

| ExtractionResult.Action | 결정 주체 | → IntentCode | tier |
|------|------|------|------|
| NEW_SEARCH | 룰 or Grok | `NEW_SEARCH(005)` | RULE or LLM_LIGHT |
| SKIP | 룰 or Grok | `SKIP(007)` | RULE or LLM_LIGHT |
| GENERATE_NOW | 룰 or Grok | `GENERATE(008)` | RULE or LLM_LIGHT |
| KEEP | Grok | **001/002/003/004** (미술 의도 세분류) | LLM_LIGHT |

- 룰이 결정한 것(NEW_SEARCH/SKIP/GENERATE_NOW via RulePreRouter)은 tier=RULE, 바로 매핑.
- Grok 이 결정한 것은 tier=LLM_LIGHT.
- **KEEP 만 추가 분류**가 필요. KEEP = "레퍼런스 유지 + 세부 질문" 인데, 그 세부 질문의 미술 의도(구도/빛/색/기법)를 분류해 001~004 로 만든다. 분류 불가/애매 시 KEEP(006) 로 안전 폴백.

---

## 2. 미술 의도 분류 위치 — 별도 콜 아님

핵심 결정: **미술 의도 분류를 위한 별도 Grok 콜을 추가하지 않는다.** 이유:

- 이미 `KeywordExtractor.extract()` 가 Grok 을 1회 호출해 4 Action 을 정한다. 여기에 KEEP 일 때 "어떤 미술 의도인지"를 **같은 응답에서 함께** 받게 프롬프트를 확장하면 콜 추가 0.
- 단, `ExtractionResult` 는 미술 의도 슬롯이 없다 → `KeywordExtractor` 가 미술 의도까지 반환하도록 바꾸면 기존 4 Action 에 의존하는 `ChatLlmService` 분기가 영향받는다(회귀 위험).

→ **단계적 접근**: 1차는 어댑터를 **`ExtractionResult` 만 입력**으로 만들고, KEEP→006(미분류) 으로 매핑. 미술 의도 세분류(001~004)는 `KeywordExtractor` 응답 확장이 필요하므로 **2차**로 분리. 1차 어댑터는 기존 흐름 0 수정, WorkflowService 연결 토대만 만든다.

> 즉 이번(②-1차): IntentResult 어댑터 + tier. 미술 의도 001~004(②-2차)는 KeywordExtractor 프롬프트 확장과 함께.

---

## 3. 산출물 (②-1차)

- `domain/llm/classifier/IntentResultAdapter.java` (신규): `adapt(RulePreRouter.Decision, ExtractionResult, boolean hasUploadedImage, List<Integer> refs) → IntentResult`. 순수 매핑(룰/LLM tier 판정 + Action→code). LLM 콜 없음.
- 단위 테스트: 4 Action × tier 매트릭스, 앵커 슬롯·업로드 플래그 전달.

**chat() 연결은 아직 안 함** — IntentResult 가 생겨도 StepContext 로 흘려 WorkflowService 를 돌리려면 COMPOSE 등 골격 Executor 가 실로직을 받아야(③ 잔여). 어댑터는 그 토대.

---

## 4. ②-2차 (다음)

- `KeywordExtractor` SYSTEM_PROMPT 확장: KEEP 일 때 미술 의도 라벨(COMPOSITION/LIGHTING/COLOR/TECHNIQUE) 동반 출력. `ExtractionResult` 에 의도 슬롯 추가 또는 별도 결과 타입.
- 어댑터가 그 라벨을 001~004 로 매핑.
- 회귀: KEEP 에 의존하는 `handleSearchDecision` 분기 영향 확인.

---

## 5. 미해결

1. ②-2차에서 미술 의도를 `ExtractionResult` 확장으로 담을지, 별도 `IntentClassification` 타입을 만들지 — 1차 어댑터를 record 입력으로 유연하게 두고 2차에 결정.
2. chat() 실연결 시 기존 `decision.action().name()`(응답에 싣는 값)과 IntentResult 의 관계 — 점진 마이그레이션(둘 다 유지 → IntentResult 로 일원화).
