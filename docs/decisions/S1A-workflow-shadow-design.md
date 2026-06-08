# S1' 트랙 A ③ — WorkflowService shadow 연결 설계

> 작성일: 2026-06-08
> 짝 문서: [`S1A-intent-classifier-design.md`](./S1A-intent-classifier-design.md), [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) §3
> 성격: 구현 설계 노트.

---

## 0. 목표

트랙 A ③의 마지막 조각. WorkflowService 를 `ChatLlmService.chat()` 에 **shadow(그림자) 모드**로 연결한다. 즉:

- **기존 chat() 흐름은 100% 그대로** 실제 응답을 낸다 (회귀 0).
- WorkflowService 를 **병렬로 한 번 더** 돌려, 같은 입력에 어떤 결과를 냈을지 **비교·로깅·메트릭**만 한다.
- 실제 사용자 응답에는 영향 없음.

**왜 shadow 인가**: 전체 갈아끼우기(chat 을 WorkflowService 경유로)는 회귀 면적이 최대다. shadow 로 먼저 "워크플로우 경로가 기존과 동등한 결과를 내는가"를 운영 데이터로 검증한 뒤, 신뢰가 쌓이면 실연결로 승격한다.

---

## 1. shadow 대상 경로 = NEW_SEARCH 만

현재 실제로 동작하는 Executor 는 B 가 만든 **EXTRACT_KEYWORDS, SEARCH** 둘뿐(COMPOSE 등은 골격). 따라서 shadow 는 이 둘이 도는 **NEW_SEARCH** 경로만 잡는다.

ROUTING.NEW_SEARCH = `[EXTRACT_KEYWORDS, SEARCH, COMPOSE]`. shadow 에서 COMPOSE 는 골격이라 통과(no-op). 실질 비교 대상은 **검색 결과**.

### 비교 포인트 — 핵심 가치

| 항목 | 기존 chat() | WorkflowService shadow |
|------|------------|----------------------|
| 키워드 출처 | Grok 이 뽑은 영문 키워드(`decision.keywords()`) | **Komoran** 형태소→사전(`EXTRACT_KEYWORDS`) |
| 검색 | `searchService.search(keywords)` | `SEARCH` (같은 SearchService) |

→ shadow 의 진짜 질문: **"Komoran 경로가 Grok 경로와 비슷한 검색 결과를 내는가?"** (트랙 B 사전 품질 검증). 결과 ref id 집합·개수·점수를 비교 로깅.

---

## 2. 연결 지점

`ChatLlmService.handleSearchDecision()` 의 NEW_SEARCH 케이스. 기존 검색 직후, shadow 를 호출:

```
case NEW_SEARCH:
  // ... 기존 searchService.search() + 스코어게이트 + 응답 (그대로) ...
  shadowWorkflow(user, project, sessionId, message, decision, 기존결과);  // 추가
  return 기존결과;
```

`shadowWorkflow` 는:
1. `IntentResult` 생성 (IntentResultAdapter — 이미 있음)
2. `StepContext.start(...)` 빌드 (userId/projectId/sessionId/rawMessage/cleanedMessage…)
3. `workflowService.run(intent, ctx)` → 결과 references
4. 기존 결과와 ref id 집합·개수 비교 → 로그 + Micrometer (`drawe.workflow.shadow`, tag: match/partial/miss)
5. **예외는 절대 밖으로 안 던짐** — shadow 가 실제 응답을 깨면 안 됨. try-catch 전체 감싸기.

---

## 3. cleanedMessage 정규화

`StepContext.start` 의 `cleanedMessage` 는 EXTRACT_KEYWORDS(Komoran)의 입력. 설계상 정규화 규칙(trim·공백압축·앵커제거)이 있으나(StepContext javadoc), shadow 1차는 **rawMessage 를 그대로 cleanedMessage 로** 넣어 단순화한다(앵커 전처리는 ① 2차 몫). 즉 shadow 는 "원문 → Komoran → 검색" 을 본다.

---

## 4. 산출물

- `ChatLlmService` 에 `RulePreRouter.Decision` 의 ruleDecided 정보가 필요 → `routeIntent` 가 현재 `ExtractionResult` 만 반환하는데, shadow tier 판정을 위해 룰/Grok 여부를 알아야 한다. **routeIntent 가 (ExtractionResult, ruleDecided) 를 함께 넘기도록** 작은 내부 구조 추가(외부 시그니처 불변).
- `shadowWorkflow(...)` private 메서드 — IntentResultAdapter + StepContext.start + workflowService.run + 비교 로깅. 의존성: `WorkflowService`, `IntentResultAdapter` 주입.
- Micrometer `drawe.workflow.shadow` (outcome: match/partial/miss/error).
- chat() 외부 동작·응답·테스트 불변 (shadow 는 부수효과만).

---

## 5. 승격 경로 (이 문서 범위 밖)

shadow 로그가 "Komoran≈Grok 검색결과 일치율 충분" 을 보이면 → COMPOSE/TRANSLATE/GENERATE_IMAGE 실로직 이관(StepContext 엔티티 경로 결정) → chat() 을 WorkflowService 경유로 승격. 그 전엔 shadow 유지.
