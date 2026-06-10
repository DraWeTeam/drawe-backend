# S2' 트랙 A 설계 — 출력 규격화 + 멀티콜 단계화

> 작성일: 2026-06-10
> 짝 문서:
> - [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) — 상위 ADR (§6 출력 검증, §9 스프린트 S2')
> - [`S1A-rule-prerouter-design.md`](./S1A-rule-prerouter-design.md) — S1' 트랙 A 분류 설계
> - [`S1A-workflow-shadow-design.md`](./S1A-workflow-shadow-design.md) — WorkflowService shadow 설계 (본 문서가 직접 이어받음)
> - [`S1-resilience4j-design.md`](./S1-resilience4j-design.md) — 외부 API 안정성 정책
>
> 이 문서의 성격: **S2' 트랙 A 실행 설계**. 상위 ADR §6·§9 의 결정을 코드 계약으로 구체화한다.
> ADR 과 충돌 시 **ADR 이 우선**한다 — 이 문서는 "어떻게" 만 다룬다.

---

## 0. 범위 (TL;DR)

ADR §9 가 S2' 트랙 A 에 할당한 것:

| Phase | ADR 정의 | 본 문서 |
|-------|---------|--------|
| **Phase 4** | Multi-call 단계화 (intent → keywords → compose) | §3 — ComposeExecutor 실연결 + 메인 경로 전환 |
| **Phase 3** | 페르소나 v2 + Structured Output 네이티브 강제 + 결정론적 참조 무결성 | §4~§6 |

**DoD (ADR §9 S2'):**
- 응답 구조 위반률 ≤ 1% (네이티브 스키마 강제로)
- 환각 인용 0건 (결정론적 검사)
- intent precision ≥ 90% (트랙 B Phase 5 메트릭과 공유)

### 진행 순서 결정 — Phase 4 먼저, 그 위에 Phase 3

ADR 표는 "Phase 3 → Phase 4" 로 적었으나 **구현은 역순**으로 한다.

**근거:** Structured Output·참조 무결성은 "LLM 을 호출하는 그 한 지점"에 붙는다.
그 지점이 지금 레거시 `ChatLlmService.chat()` (`llm.generate(ctx)`, 약 L157) 에 있고,
Phase 4 에서 그것을 `ComposeExecutor` 로 옮기는 중이다.
Phase 3 을 먼저 하면 **레거시와 신규 양쪽에 structured output 을 두 번 구현**하게 된다.
→ Phase 4 로 호출 지점을 ComposeExecutor 로 일원화한 뒤, 그 한 곳에 Phase 3 을 붙인다.

### 프로바이더 결정 — Grok / OpenAI 호환 `response_format`

ADR §6 은 "Anthropic: tool_use / OpenAI 호환: `response_format`" 둘 다 열어뒀다.
**이번 작업의 메인은 Grok** (`ChatLlmService` 가 `resolveProvider` 로 고르지만 합성 응답의 기본은 Grok 경로).
→ `GrokService.buildBody()` 에 `response_format: { type: "json_schema" }` 를 1차로 구현한다.
Claude/Gemini 의 structured output 은 본 스프린트 범위 밖(폴백은 §6.4 안전 템플릿이 흡수).

---

## 1. 현재 상태 (S2' 진입 시점)

조사 기준 커밋: `7582822` (S1' 트랙 A ① 완료).

| 영역 | 현황 | 출처 |
|------|------|------|
| 메인 LLM 호출 | `LlmService.generate(LlmCallContext)` → Grok 평문, `extractText()` 로 content 만 추출 | `GrokService.java:44-129` |
| 호출 지점 | `ChatLlmService.chat()` 의 `llm.generate(ctx)` | `ChatLlmService.java:157` |
| structured output | ❌ 없음 (`buildBody` 가 model+messages 만) | `GrokService.java:84-109` |
| 참조 무결성 | ❌ 없음 — content 를 그대로 클라이언트로 반환 | `ChatLlmService.java:193-201` |
| 페르소나 | `PersonaRegistry` `FRIENDLY_01` 1종, 인용 규칙 산문으로만 명시 | `PersonaRegistry.java` |
| WorkflowService | 인프라 완비, **NEW_SEARCH 만 shadow** | `WorkflowService.java`, `ChatLlmService.shadowWorkflow()` |
| ComposeExecutor | **골격(stub)** — ctx 통과만 | `workflow/executor/ComposeExecutor.java` |
| StepContext | history / persona / provider / image 필드 **없음** | `contract/StepContext.java` |
| LlmCallContext | `history + newPrompt + imageBytes + imageMimeType` | `dto/LlmCallContext.java` |

**핵심 간극:** ComposeExecutor 가 LLM 합성을 떠안으려면 `StepContext` 가
지금 `ChatLlmService` 만 아는 정보(history, image, provider 선택)를 실어야 한다.

---

## 2. 설계 원칙

1. **호출 지점 일원화** — 메인 LLM 합성은 `ComposeExecutor` 한 곳에서만 일어난다. 레거시 `chat()` 의 직접 호출은 전환 후 제거.
2. **재호출 없음** (ADR §6) — 구조는 네이티브 스키마로 강제, 무결성은 결정론적 코드로. ResponseValidator 재호출 루프 금지.
3. **위반은 통과 + 정정** — 환각 인용은 응답을 깨지 않고 "해당 인용만 제거" 후 통과. 깨진 JSON 은 안전 템플릿으로 평문화.
4. **메트릭 박제** — 구조 위반·환각 인용·인용 제거는 전부 Micrometer 카운터로. DoD 측정 가능해야 함.
5. **트랙 B 비파괴** — `StepContext` 확장은 추가만(기존 필드/wither 불변). B 의 EXTRACT_KEYWORDS·SEARCH 가 안 깨지게.

---

## 3. Phase 4 — 멀티콜 단계화 (ComposeExecutor 실연결)

### 3.1 StepContext 확장 (추가만)

ComposeExecutor 가 LLM 을 부르려면 다음이 필요하다. **기존 필드 뒤에 추가**한다(트랙 B 무영향).

```java
// ── 누적/입력: A 의 COMPOSE 가 읽는 필드 (S2' 추가) ──
List<LlmCallContext.Turn> history,   // persona/userPrefs/projectContext SYSTEM turn 포함된 누적 히스토리
byte[] uploadedImageBytes,           // 멀티모달 입력 (uploadedImageUrl 해석 결과)
String uploadedImageMimeType,
LlmProvider provider                 // resolveProvider(user) 결과를 분류 단계에서 미리 실어둠
```

- `history` 는 ChatLlmService 가 지금 만드는 것과 동일한 방식으로 분류 단계에서 구성해 싣는다(persona·userPrefs·projectContext·referenceContext SYSTEM turn 포함).
- `references` (트랙 B 가 SEARCH 로 채움) 를 referenceContext SYSTEM turn 으로 바꾸는 책임은 **ComposeExecutor** 가 가진다 (현재 `buildReferenceContext` 로직 이관).
- wither (`withHistory` 등) 는 `@With` 가 자동 생성.

> **합의 필요(트랙 B):** `StepContext` 필드 추가는 양쪽 합의 대상(클래스 주석 명시). B 는 추가 필드를 **읽지 않으므로** 안전하지만, record 생성자 시그니처가 바뀌므로 `start(...)` 팩토리와 **모든 생성 지점을 함께 갱신**한다. 현재 생성 지점(커밋 ① 에서 동시 수정):
> - `ChatLlmService.shadowWorkflow()` — `StepContext.start(...)` (L435)
> - `WorkflowServiceTest` — `StepContext.start(...)`
> - `ExtractKeywordsExecutorTest`, `SearchExecutorTest` — `new StepContext(...)` 직접 생성
>
> 추가 필드는 nullable 기본값 + `start()` 오버로드 보존(기존 8-인자 호출이 안 깨지게)으로 B 의 테스트가 시그니처 변경에 영향받지 않게 한다.

### 3.2 ComposeExecutor 구현

```java
@Override
public StepContext execute(StepContext ctx) {
    if (ctx.composedAnswer() != null) return ctx;   // 멱등

    // 1. references → referenceContext SYSTEM turn (이관: buildReferenceContext)
    List<Turn> history = withReferenceContext(ctx.history(), ctx.references());

    // 2. LLM 호출 (structured output 강제 — Phase 3, §4)
    LlmCallContext call = new LlmCallContext(
        history, ctx.cleanedMessage(), ctx.uploadedImageBytes(), ctx.uploadedImageMimeType());
    LlmCallResult result = llm(ctx.provider()).generate(call);

    // 3. structured 응답 파싱 + 참조 무결성 (Phase 3, §5)
    ComposedOutput out = outputParser.parse(result.content(), ctx.references());

    return ctx.withComposedAnswer(out.message());   // citations/offerGenerate 도 슬롯에
}
```

- LLM 선택은 `provider()` 슬롯으로 — ComposeExecutor 가 `Map<LlmProvider, LlmService>` 를 주입받는다 (Spring 자동 수집, WorkflowService 의 executors 주입과 동형).
- 메트릭(`drawe.workflow.step` step=COMPOSE)은 WorkflowService.runStep 이 이미 감싼다.

### 3.3 메인 경로 전환 (shadow 제거)

현재 `chat()` 은 레거시 직접 호출 + `shadowWorkflow()` 병렬. 전환 절차:

1. **분류 단계에서 StepContext 완성** — `routeIntent` 결과 + history + image + provider 를 `IntentResultAdapter` 로 IntentResult/StepContext 에 싣는다.
2. **`workflowService.run(intent, ctx)` 를 메인으로** — 결과 `finalCtx.composedAnswer()` 등을 ChatResponse 로 매핑.
3. **레거시 분기 제거** — `handleSearchDecision` + 직접 `llm.generate` + `shadowWorkflow` 삭제. (단, `GENERATE_NOW` 분기는 GENERATE 라우팅(TRANSLATE→GENERATE_IMAGE)이 실연결되기 전까지 **유지** — 본 스프린트는 COMPOSE 경로만 전환, TRANSLATE/GENERATE_IMAGE 는 ADR 상 Phase 4 이후/S3'.)

> **점진 전환 안전장치:** NEW_SEARCH·KEEP·SKIP·001~004(전부 COMPOSE 종착) 부터 메인 전환. 한 의도라도 shadow outcome 이 `match` 가 아니면 그 의도는 레거시 유지하고 원인 분석. 플래그(`workflow.compose.live`)로 의도별 토글.

### 3.4 이번 스프린트에서 **안 하는 것**

- TRANSLATE / GENERATE_IMAGE Executor 실연결 (생성계, ADR §8 — Phase 4 이후)
- CRITIQUE_UPLOAD (S3', 멀티모달)
- 복합 의도 `Set<IntentCode>` (ADR §5 — 베타 빈도 ≥10% 조건 미충족)

---

## 4. Phase 3-a — Structured Output 네이티브 강제 (Grok)

### 4.1 응답 스키마

```jsonc
{
  "type": "object",
  "properties": {
    "message":       { "type": "string" },              // 사용자에게 보일 가이드 본문
    "citations":     { "type": "array", "items": { "type": "integer" } },  // 인용한 references 1-based 인덱스
    "offer_generate":{ "type": "boolean" }              // 자료 부족 → 생성 제안 (LLM 의견; 시스템이 최종 결정)
  },
  "required": ["message", "citations"],
  "additionalProperties": false
}
```

- `citations` 는 **본문에 실제로 인용한** 인덱스만. 무결성 검사(§5)의 입력.
- `offer_generate` 는 참고용 — 최종 버튼 노출은 시스템(references.isEmpty() + mentionsGenerateOffer)이 결정. LLM 값은 보조 신호.

### 4.2 GrokService.buildBody 확장

```java
body.put("model", model);
body.put("messages", messages);
body.put("response_format", Map.of(
    "type", "json_schema",
    "json_schema", Map.of(
        "name", "draw_guide_response",
        "strict", true,
        "schema", SCHEMA)));   // §4.1
```

- `strict: true` 로 스키마 위반을 API 레벨에서 차단.
- `LlmService.generate` 시그니처는 불변 — structured 여부는 호출자(ComposeExecutor)가 별도 플래그/메서드로 요청하거나, `LlmCallContext` 에 `responseSchema` 옵셔널 필드 추가. **결정: `LlmCallContext` 에 `String responseSchemaName`(null 이면 평문) 추가** — TRANSLATE 등 다른 호출은 평문 유지해야 하므로 호출별 선택 가능해야 함.

### 4.3 폴백 (모델 미지원 / 깨진 JSON)

- Grok 이 `response_format` 미지원 응답을 주면 → §6.4 안전 템플릿.
- `strict` 거부(HTTP 4xx)는 `AI_SERVICE_ERROR` 로 기존 경로 흡수.

---

## 5. Phase 3-b — 결정론적 참조 무결성

### 5.1 검사 규칙 (재호출 없음)

`OutputIntegrityChecker.check(ComposedOutput raw, List<ReferenceImage> refs)`:

1. **유효 인덱스 집합** = `{1 .. refs.size()}`.
2. **citations 검증** — `raw.citations()` 중 유효 집합 밖 인덱스 = **환각 인용**.
3. **본문 인용 스캔** — `message` 에서 정규식 `\[(\d+)\]` 추출. 유효 집합 밖 = 환각.
4. **정정** — 환각 인용을 본문에서 **해당 토큰만 제거**(`[4]` → 삭제, 문장 흐름 보존 위해 앞뒤 공백 정리). `citations` 슬롯에서도 제거.
5. **no-refs 위반** — `refs.isEmpty()` 인데 인용이 있으면 전부 환각 → 전부 제거.
6. **결과** = 정정된 message + 살아남은 citations + 위반 카운트.

> ADR §6.3 그대로: "위반 시 → 해당 인용만 제거하고 응답 통과". 문장 전체 삭제 아님.

### 5.2 메트릭 (DoD 측정)

| 카운터 | 태그 | DoD |
|--------|------|-----|
| `drawe.output.structure_violation` | provider, reason(json_broke/schema_reject) | ≤ 1% |
| `drawe.output.hallucinated_citation` | source(citations_field/body_scan/no_refs) | **0건** |
| `drawe.output.citation_removed` | — | 관측용 |

- 분모는 COMPOSE 호출 수(`drawe.workflow.step{step=COMPOSE}` count)로 비율 산출.
- 환각 인용 0건이 DoD 이므로, 1건이라도 카운트되면 알림 대상.

---

## 6. Phase 3-c — 페르소나 v2 + 안전 템플릿

### 6.1 페르소나 v2 (인용 규칙 강화)

현재 `FRIENDLY_01` 의 인용 규칙은 산문. v2 는 **structured output 과 정합**하게 강화:

- "인용은 `citations` 배열과 본문 `[N]` 이 일치해야 한다."
- "제공된 참고 이미지 개수 밖의 번호를 쓰지 마라 (시스템이 제거하고 메트릭에 기록한다)."
- "참고 이미지가 없으면 `citations` 는 빈 배열, 본문에 `[N]` 금지."

`PersonaRegistry` 에 `FRIENDLY_02` 추가, `DEFAULT_KEY` 전환은 A/B 관측 후. (v1 보존 — 롤백 가능.)

### 6.2 깨진 JSON 안전 템플릿 (ADR §6.4)

`response_format strict` 로 거의 안 깨지지만, 폴백 보증:

- JSON 파싱 실패 → 원본 노출 **금지**.
- 결정론적 템플릿: `"조언을 정리하다 문제가 생겼어요. 다시 한 번 말씀해 주실래요?"` + references 는 그대로 노출(검색 자체는 성공했으므로).
- `structure_violation{reason=json_broke}` 카운트.

---

## 7. 작업 분해 (커밋 단위)

> S1' 트랙 A 처럼 작은 커밋으로. 각 커밋은 빌드·테스트 통과.

| # | 커밋 | 범위 | 선행 |
|---|------|------|------|
| ① | StepContext 확장 (history/image/provider 필드 + 팩토리·**호출부·테스트 동시 갱신**) | Phase 4 | — |
| ② | LlmCallContext.responseSchemaName + GrokService response_format | Phase 3-a | — (①과 독립) |
| ③ | OutputParser + OutputIntegrityChecker (단위 테스트 중심) | Phase 3-b | ② |
| ④ | ComposeExecutor 실연결 (history/refContext/LLM/파서 결선) | Phase 4 | ①②③ |
| ⑤ | 메인 경로 전환 — NEW_SEARCH 부터 live, 플래그 토글 | Phase 4 | ④ |
| ⑥ | PersonaRegistry FRIENDLY_02 + 안전 템플릿 | Phase 3-c | ④ |
| ⑦ | 메트릭 카운터 3종 + 의도별 live 확대 | DoD | ⑤⑥ |

③ 은 ④ 없이도 순수 단위 테스트 가능(파서·체커는 입력 문자열 + refs 리스트만 받음) — 먼저 박아두면 ④ 가 쉬워진다.

---

## 8. 리스크 & 완화

| 리스크 | 완화 |
|--------|------|
| Grok `response_format strict` 가 미술 톤 답변 품질을 떨어뜨림 | ⑤ 의도별 토글로 점진 전환, shadow outcome 비교. 품질 저하 시 schema 만 유지·strict 완화 |
| StepContext 시그니처 변경이 트랙 B 빌드 깸 | ① 에서 `start()` 팩토리 + 모든 호출부 동시 갱신, 추가 필드는 nullable 기본값. B 와 머지 순서 합의 |
| ComposeExecutor 가 ChatLlmService 의 부수효과(메시지 저장·analytics)까지 떠안아야 함 | 저장/analytics 는 ChatLlmService 에 남기고 ComposeExecutor 는 **순수 합성만**. finalCtx → ChatResponse 매핑 지점에서 저장 |
| 레거시·신규 이중 경로가 한동안 공존 | ⑤ 플래그로 명확히 분리, shadow 메트릭으로 동치 확인 후에만 레거시 삭제 |

---

## 9. 강사님께 재확인 (S2' 종료 후)

1. Structured Output 강제로 인한 답변 톤 변화 — 허용 범위인지
2. 환각 인용 0건 DoD — 베타 로그에서 실제 0 달성 여부
3. 페르소나 v2 전환 시점 — A/B 관측 기준

---

## 10. 변경 이력
- 2026-06-10 초안 — ADR §6·§9 S2' 트랙 A 를 코드 계약으로 구체화. 진행 순서 Phase 4→3 역전, 프로바이더 Grok `response_format` 확정.
