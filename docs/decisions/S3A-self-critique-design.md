# S3' 트랙 A — 010 SELF_CRITIQUE 1차 설계 (a1 + b1)

> 작성일: 2026-06-16
> 상위 ADR: [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) §9 (S3' — 빠진 의도 코드 채우기)
> 선행: S1' 트랙 A(룰 프리라우터·intent 어댑터), S2' 트랙 A(ComposeExecutor 실연결·live 게이트)
> 짝 설계: [`S1A-intent-classifier-design.md`](./S1A-intent-classifier-design.md), [`S2A-output-contract-design.md`](./S2A-output-contract-design.md)
>
> **상태: 초안 — 합의 대기.** 코드 미착수.

---

## 0. 한 줄 요약

> 사용자가 **본인 작업물을 업로드 + 평가 요청**하면 `010 SELF_CRITIQUE` 로 분류해, 업로드 이미지를
> LLM 비전으로 직접 비평한다. **1차는 LLM 비전만**(a1) — CLIP 임베딩·유사 레퍼런스 첨부는 2차로 분리.
> 분류 트리거는 **"이미지 있음 + 비평 요청 신호" 결정론적 룰**(b1) — LLM 콜 0.

---

## 1. 배경 — 무엇이 이미 있고 무엇이 비었나 (2026-06-16 코드 조사)

`010` 의 멀티모달 **입력 배관은 이미 끝까지 깔려 있다.** 막힌 곳은 **분류**와 **비평 실행 로직** 둘뿐이다.

| 경로 단계 | 상태 | 근거 |
|------|------|------|
| 챗 요청 이미지 필드 | ✅ | `ChatRequest.imageUrl` (data-URL 또는 `/images/{id}`) |
| 디코딩 → bytes+mime | ✅ 동작 | `ImageInputResolver.resolve()` → `Resolved(bytes, mime, storedUrl)` (소유자 검증 포함) |
| 챗 흐름이 매 턴 호출 | ✅ 동작 | `ChatLlmService:92` |
| LLM 멀티모달 전송 | ✅ 동작 | `GrokService:92-99` (bytes → data-URI `image_url` 파트) |
| live 경로가 bytes 운반 | ✅ 동작 | `chatViaWorkflow` → `startForCompose(... image.bytes(), image.mimeType() ...)` → `ComposeExecutor:103-105` 가 `LlmCallContext` 에 실어 비전 호출 |
| CLIP 이미지 임베딩 | ✅ 존재·운영중 | `FastApiClient.embedImage` — `AiImageIndexService:82` 에서 이미 사용 (2차 a2 에서 재사용) |

**막힌 두 곳:**

1. **분류** — `IntentResultAdapter.toCode()` 는 레거시 4 Action(`NEW_SEARCH/KEEP/SKIP/GENERATE_NOW`)에서만
   IntentCode 를 만든다. `010` 을 **구조적으로 못 뱉는다.** `adapt(...)` 시그니처에 `hasUploadedImage`
   플래그는 이미 들어오지만(트랙 A ② 때 슬롯만 마련), 그걸 보고 `SELF_CRITIQUE` 로 분기하는 로직은 없다.
2. **비평 실행** — `CritiqueUploadExecutor` 는 `return ctx` 골격(`CRITIQUE_UPLOAD 골격 — 미구현`).

**중요 — ADR 의 "A+B 공동" 재해석:** ADR §9 는 `010` 을 A+B 로 잡았다. 그 B 몫은 "업로드 이미지 임베딩"
(`embedImage`)인데, 이건 AI 이미지 적재용으로 **이미 만들어져 운영 검증까지 끝난 코드**다. 따라서
**1차(a1)에는 신규 B 작업이 필요 없다.** B 의존은 2차(a2, 유사 레퍼런스 첨부)에서만 발생한다.

---

## 2. 결정 — a1 + b1

### 2.1 a1: 비평은 LLM 비전만 (1차)

`CRITIQUE_UPLOAD` 라우팅(`[CRITIQUE_UPLOAD, COMPOSE]`)은 그대로 두되, **실제 비전 호출은 후속 COMPOSE 가
이미 한다.** `ComposeExecutor` 가 `ctx.uploadedImageBytes()` 를 `LlmCallContext` 에 실어 보내므로(검증됨),
1차에서 `CritiqueUploadExecutor` 가 할 일은 **"비평 모드" SYSTEM turn 을 history 에 주입**하는 것뿐이다.

- `CritiqueUploadExecutor.execute()`: 업로드 이미지가 있으면(`ctx.uploadedImageBytes()` 비어있지 않으면)
  비평 가이드 SYSTEM turn 을 `ctx.history` 끝에 추가한 새 ctx 를 반환. 이미지가 없으면(방어) 통과.
- 비평 가이드 turn 내용(초안): "사용자가 **본인 작업물**을 올렸다. 구도/명암/색/형태 관점에서 **구체적이고
  건설적인** 피드백을 1순위로. 잘된 점 1개 + 개선점 1~2개. 페르소나 [자율성 경계]의 '평가 금지'는 이
  의도에서만 해제된다(사용자가 평가를 명시 요청했으므로)." — 단정·강요 톤 금지는 유지.
- COMPOSE 의 structured output(draw_guide_response: message/citations/offer_generate)을 그대로 쓴다.
  비평엔 검색 레퍼런스가 없으니 `citations=[]`(빈 배열) — 이미 `buildReferenceContext(refs=empty)` 가
  "참고 없음 + [N] 금지" 안내를 붙이므로 환각 인용은 결정론적 체커가 그대로 차단한다(추가 작업 불요).

**왜 CritiqueUpload 를 별도 step 으로 두나(COMPOSE 페르소나에 다 넣지 않고):** 비평 가이드는
의도 종속 컨텍스트라, 의도별 SYSTEM turn 주입 책임을 step 으로 분리하면 011~013 도 같은 패턴으로 확장된다.
COMPOSE 는 "페르소나+레퍼런스+호출"의 순수 합성으로 유지(S2' §3.2 책임 절단선 보존).

### 2.2 b1: 분류는 결정론적 룰 (1차)

`010` 은 **강한 결정 신호**(`hasImage`)가 있어 LLM 콜 없이 정확히 잡힌다. `RulePreRouter` 1차 범위가
"TERMINAL = LLM 콜 0" 인 원칙과 정합.

- 트리거 조건: **업로드 이미지 있음** AND **비평 요청 신호**.
  - 비평 요청 신호(룰): "어때", "평가", "봐줘/봐주세요", "피드백", "어떤 것 같아", "괜찮아?", "고칠/고칠 점",
    "잘 그렸/잘됐", "critique", "feedback", "review" 등. (베타 로그 표현으로 보강 — §5)
- 단, `RulePreRouter.route(userMessage, history)` 는 현재 **이미지 유무를 모른다**(메시지만 받음).
  → **`hasImage` 인자를 route 시그니처에 추가**하거나, ChatLlmService 가 `image.hasImage()` 를 알고 있으니
    호출부에서 조합한다. 후자가 변경 최소(§3.2 결정).

**룰 미스 시:** 이미지가 있는데 비평 신호가 약하면(예: 이미지+"이 색감 어디서 영감받았어?") `010` 으로
단정하지 않고 기존 경로로 흘린다(레거시 KEEP/COMPOSE). 즉 b1 은 **명확한 비평 요청만** 010 으로 끌어올리고
나머지는 회귀 없이 기존 동작 유지(프리라우터 설계 철학 그대로).

---

## 3. 변경 지점 (코드)

### 3.1 분류 산출이 010 을 만들 수 있게

| 변경 | 파일 | 내용 |
|------|------|------|
| 010 분류 트리거 | `RulePreRouter` 또는 호출부 | hasImage + 비평신호 → SELF_CRITIQUE 결정. **시그니처/책임 위치는 §3.2 합의** |
| 어댑터 010 경로 | `IntentResultAdapter` | 룰이 010 을 직접 결정하면 `ExtractionResult` 에 그걸 표현할 길이 필요(§3.2). tier=RULE |

### 3.2 ⚠️ 합의 필요 — 010 을 분류 파이프라인에 어떻게 표현하나

현재 분류 산출 타입은 `ExtractionResult`(4 Action enum) 다. `010` 은 그 enum에 없다. 세 가지 길:

- **(가) `ExtractionResult.Action` 에 `SELF_CRITIQUE` 추가** — 가장 직관적이나 레거시 chat() 의 4-Action
  switch 들(`handleSearchDecision` 등)이 다 새 case 를 처리해야 함(누락 시 컴파일 경고/런타임 갭). 영향 넓음.
- **(나) IntentResultAdapter 가 hasUploadedImage + (룰 신호 플래그)로 010 을 직접 산출** — `ExtractionResult`
  는 안 건드리고, adapt() 입력에 "비평 요청" 신호를 하나 더 받아 010 을 만든다. 레거시 경로는 010 을 모르고
  지나가도 됨(010 은 **live 게이트로만** 들어오므로 — §3.3). 영향 가장 좁음. **추천.**
- **(다) 별도 분류기 신설** — 과함. 1차엔 불요.

> **이 문서의 잠정 채택: (나).** 근거 — `010` 은 live 워크플로에서만 도달(레거시는 멀티모달 비평 미지원).
> 레거시 4-Action switch 를 건드리지 않아 회귀면이 0. 단 (가)는 011~013 까지 갈 때 재검토.

### 3.3 실행 + 게이트

| 변경 | 파일 | 내용 | 기존 동작 |
|------|------|------|----------|
| 비평 SYSTEM turn 주입 | `CritiqueUploadExecutor` | 골격 → 업로드 이미지 있으면 비평 가이드 turn 을 history 에 추가 | 없으면 통과(불변) |
| live 도달 | `chatViaWorkflow` | 이미 `intent`/`image.bytes()` 를 `startForCompose` 로 운반 — **추가 배선 거의 없음** | 불변 |
| 게이트 | `WorkflowComposeProperties` | `010 SELF_CRITIQUE` 도 `liveIntents` 에 들어와야 live. **기본 off.** | 불변 |

> **R1 방어와의 정합:** `WorkflowComposeProperties.@PostConstruct validateLiveIntents` 는 "COMPOSE 미종착
> 의도가 liveIntents 에 있으면 부팅 실패" 다. `010` 라우팅은 `[CRITIQUE_UPLOAD, COMPOSE]` 로 **COMPOSE 종착**
> 이라 이 검증을 통과한다(GENERATE 처럼 막히지 않음). 확인 완료.

---

## 4. 흐름 (1차, a1+b1)

```
[업로드+비평요청]
  ChatRequest{message:"이거 어때?", imageUrl:"data:image/png;base64,..."}
    → ImageInputResolver.resolve → Resolved(bytes, mime)
    → RulePreRouter/조합: hasImage && 비평신호 → SELF_CRITIQUE (tier=RULE, LLM콜 0)
    → IntentResultAdapter (나): IntentResult{code=010, hasUploadedImage=true}
    → WorkflowComposeProperties.isLive(010)?
         off → (1차엔 010 이 레거시 경로엔 핸들러 없음 → §6 안전판단)
         on  → chatViaWorkflow
                 → startForCompose(... bytes, mime ...)
                 → WorkflowService.run(010): [CRITIQUE_UPLOAD → COMPOSE]
                      CritiqueUploadExecutor: 비평 가이드 SYSTEM turn 주입
                      ComposeExecutor: 페르소나 + 비평 turn + 업로드 이미지(비전) → 구조화 비평
                 → ChatResponse{message: 비평, citations: [], offerGenerate: false}
```

---

## 5. 베타 데이터 의존 (확인 필요)

- 비평 요청 신호 어휘(§2.2)는 **베타 로그의 실제 표현**으로 보강해야 정밀도가 산다. ADR §9 가 011~013 을
  "베타 로그 빈도 확인된 것만"으로 잡은 것과 같은 원칙. → **010 트리거 어휘 셋을 베타 로그에서 추출**하는
  작업이 선행되면 좋음(없으면 보수적 어휘로 시작 후 미스율 메트릭으로 튜닝).

---

## 6. 안전 / 엣지

- **이미지 없이 비평 신호만** ("내 그림 어때?" + 이미지 X): 010 트리거 안 됨(hasImage=false) → 기존 경로.
  COMPOSE 가 "이미지를 못 봤다"는 톤으로 안내(페르소나). 1차 OK.
- **010 인데 게이트 off:** (나) 채택 시 레거시 경로엔 010 핸들러가 없다. → **결정 필요:** off 일 때 010 을
  애초에 만들지 않고 기존 분류로 흘릴지(=게이트가 분류보다 먼저), 아니면 만들되 레거시에서 안전 폴백할지.
  **잠정:** 분류 단계에서 010 산출 자체를 `isLive(010)` 일 때만 하도록 게이트를 분류 앞에 둔다(off=완전 무영향).
- **민감/부적절 업로드 이미지:** 1차 범위 밖. LLM 자체 거부에 의존. 별도 모더레이션은 후속.

---

## 7. 범위 밖 (2차 이후)

- **a2: CLIP 임베딩 + 유사 레퍼런스 첨부** — `embedImage(bytes,mime)` → Pinecone 검색 → 비슷한 레퍼런스를
  비평에 함께 제시. `CritiqueUploadExecutor` 가 `embedImage` 직접 호출 → `ctx.references` 채움 → COMPOSE 가
  인용. (B 의존 발생 지점이지만 코드는 이미 존재 — 재사용.)
- **000 OUT_OF_DOMAIN / 011~013** — 별도 문서/스코프.
- **(가) ExtractionResult.Action 확장** — 011~013 도입 시 함께 재검토.

---

## 8. DoD (1차)

- [ ] "이미지 + 비평요청" → 010 분류 (LLM 콜 0, tier=RULE) — 단위테스트
- [ ] `CritiqueUploadExecutor` 가 비평 SYSTEM turn 주입 — 단위테스트
- [ ] live(010 on) e2e: 업로드 이미지 → 비전 비평 텍스트 반환, citations=[], 환각 인용 0
- [ ] 게이트 off 시 010 미발생(완전 무영향) 확인
- [ ] 비평신호 약함/이미지 없음 → 기존 경로 회귀 없음

---

## 9. 작업 순서 (커밋 제안)

1. ① `CritiqueUploadExecutor` 비평 turn 주입 + 단위테스트 (가장 독립적, 회귀면 0)
2. ② 010 분류 트리거 + 게이트 앞단 배치 + `IntentResultAdapter` (나) 경로 + 단위테스트
3. ③ docker live e2e (010 게이트 on, 업로드 이미지로 비평 확인)
4. ④ 베타 어휘 보강(데이터 확보 시)

작은 커밋 + 매 커밋 빌드·테스트. `git commit -F .git/MSG.txt`. spotless 전체재포맷 주의(선별 add).
