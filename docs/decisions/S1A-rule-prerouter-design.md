# S1' 트랙 A — 룰 프리라우터 설계

> 작성일: 2026-06-08
> 짝 문서: [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) (ADR §4) — 이 설계는 ADR §4 "Intent 분류: 하이브리드(룰+LLM)"의 구현 분해다.
> 성격: 구현 설계 노트. 코드 착수 전 합의용.

---

## 0. 목표

ADR §9 트랙 A Phase 1의 첫 조각. 현재 모든 채팅 메시지가 무조건 Grok(`KeywordExtractor.extract`)을 거쳐 분류된다. **명확한 기능 신호는 LLM 콜 없이 결정론적 룰로 먼저 분류**해서:

- LLM 콜 수·latency·비용 절감
- ADR DoD 측정 시작: **룰 명확 신호 적중률 ≥ 30%**

**비목표(이번 범위 아님):**
- contract `IntentCode`/`IntentRouting`/`StepExecutor` 런타임 연결 (= 트랙 A 조각 ②③)
- 검색 스코어 게이트 (= 트랙 B)
- Komoran·사전 (= 트랙 B)

---

## 1. 끼어드는 자리 (최소 침습)

```
ChatLlmService.chat()
  └─ keywordExtractor.extract(message, history)   ← 유일한 분류 진입점 (ChatLlmService.java:79)
       │
       ├─ [신규] RulePreRouter.route(message, history)   ← Grok 호출 직전
       │     ├─ TERMINAL  → ExtractionResult 즉시 반환 (LLM 0콜)
       │     ├─ NEEDS_KEYWORDS → Grok 을 "키워드만" 모드로 호출
       │     └─ MISS     → 기존 Grok 풀 호출 (분류+키워드)
       │
       └─ Grok 호출 (기존 경로, 폴백)
```

**핵심 원칙: 반환 타입 `ExtractionResult` 불변.** 하위 시스템(`handleSearchDecision`, `handleGenerateNow`, 응답 `decision.action().name()`)이 4개 Action에 의존하므로 건드리지 않는다. 룰은 곧장 기존 4개 Action으로 매핑한다 — `IntentCode`는 아직 도입 안 함.

---

## 2. 룰 결과 3분류

ADR §4 표의 신호를 LLM 의존도로 나눈다.

### 2.1 TERMINAL — 룰이 끝까지 결정 (LLM 0콜)

| 신호 | 룰 | → Action |
|------|-----|---------|
| 인사·감사·확인 | "고마워", "감사", "ㄳ", "ㅇㅋ", "ㅋㅋ"(단독), "굿", "좋아"(단독) | `SKIP` |
| 명시적 생성 동사 | "그려줘", "만들어줘", "생성해줘", "그려줄래", "만들어줄래", "make it", "generate" | `GENERATE_NOW` |

GENERATE_NOW 의 keywords 에는 **사용자 원문(한국어)을 그대로** 담는다. 확인 결과 `ImageGenerationService.generate()` 가 내부에서 `PromptTranslator.translate()` 로 무조건 번역하므로(`ImageGenerationService.java:50`, 주석에 "prompt 가 사용자 한국어 원문일 수도 있어"), 영문 프롬프트를 룰이 만들 필요가 없다. → **GENERATE_NOW 는 TERMINAL (LLM 0콜).**

### 2.2 NEEDS_KEYWORDS — 의도는 룰, 키워드는 LLM

| 신호 | 룰 | → Action |
|------|-----|---------|
| 추가/다른 레퍼런스 | "다른 거", "더 보여줘", "또", "추가로", "another", "more" | `NEW_SEARCH` |
| [N]번 앵커 | 정규식 `\[?\d+\]?번` + ("같은"/"비슷"/"처럼"/"더") | `NEW_SEARCH` |

의도(NEW_SEARCH)는 확정이지만 검색 키워드는 직전 대화에서 뽑아야 한다 → Grok 을 **키워드 추출 전용**으로 호출. LLM 콜은 남지만 분류 부담이 빠져 프롬프트가 짧아지고 오분류가 준다.

> ⚠️ "[N]번 어떻게 그려"(기법 질문)는 KEEP 이어야 하는데 위 앵커 룰에 안 걸리게 해야 한다. → 앵커 룰은 "같은/비슷/처럼/더" 동반 시에만 발화. 단독 "[N]번..."은 MISS 로 흘려보내 LLM 이 판단.

### 2.3 MISS — 기존 Grok 풀 호출

위에 안 걸리면 전부 기존 경로. 미술 의도 분류(구도/빛/색/기법), 복합 의도, 애매한 표현은 전부 LLM 이 판단. **회귀 위험 0** (기존과 동일).

---

## 3. 우선순위·충돌 규칙

ADR §4 의 함정("빛"룰이 "빛나는 색감"에도 발화) 회피:

1. **생성 동사 > 검색 신호**: "비슷한 거 만들어줘" → GENERATE_NOW (NEW_SEARCH 아님). 생성 동사 매치를 먼저 검사.
2. **KEEP 보호**: 기법/세부 질문("더 자세히", "어떻게 그려", "[N]번 색감")은 룰로 안 잡는다 → MISS → LLM. "더 보여줘"(NEW_SEARCH)와 "더 자세히"(KEEP) 구분은 LLM 에 맡기는 게 안전한 경계는 LLM 으로.
3. **단독성 검사**: "좋아"(SKIP) vs "이 구도 좋아 보이는데 어떻게?"(KEEP) — SKIP 룰은 **메시지가 짧고 해당 토큰이 거의 전부일 때만** 발화 (예: 길이 ≤ 6자 또는 단일 토큰).

---

## 4. 측정 (ADR DoD)

룰 히트/미스를 analytics 로 집계. `AnalyticsEventType` 에 추가:

- `INTENT_RULE_HIT` — payload: `{ rule_id, action, needs_keywords }`
- `INTENT_RULE_MISS` — payload: `{ message_length }`

**적중률 = RULE_HIT / (HIT + MISS).** DoD ≥ 30%. precision(룰이 맞췄나)은 베타 후 샘플 라벨링으로 별도 측정(ADR §4 메트릭 표).

---

## 5. 착수 전 확인할 것 (코드 보기 전 미해결)

1. ~~GENERATE_NOW 의 keywords 계약~~ **해소**: `ImageGenerationService.generate()` 가 한국어 원문을 받아 `PromptTranslator` 로 번역하므로(`ImageGenerationService.java:50`), 룰은 원문을 그대로 담아 GENERATE_NOW 를 TERMINAL 처리한다.
2. **history 형태**: NEEDS_KEYWORDS 에서 Grok "키워드만" 모드 프롬프트를 새로 만들지, 기존 SYSTEM_PROMPT 재사용할지. → 1차는 **기존 SYSTEM_PROMPT 재사용**(별도 프롬프트 안 만듦, 회귀 위험 최소). NEEDS_KEYWORDS 는 사실상 "기존 Grok 호출과 동일하되 결과 Action 을 NEW_SEARCH 로 신뢰" — 즉 1차 구현에서는 MISS 와 합쳐도 된다(아래 참조).
3. **SKIP 오발화 비용**: SKIP 은 LLM 답변까지 건너뛰므로 오발화 시 사용자 무응답 체감. 보수적으로(짧은 단독 토큰만).

### 5.1 1차 구현 단순화 결정

NEEDS_KEYWORDS 는 어차피 Grok 을 호출하므로, **1차에서는 TERMINAL(SKIP/GENERATE_NOW)만 룰로 빼고 나머지는 전부 기존 Grok 경로로 둔다.** NEW_SEARCH/앵커의 "의도만 룰" 최적화는 LLM 콜을 없애지 못해 이득이 적고(프롬프트 단축 효과뿐) 오분류 위험만 추가하므로 **2차로 미룬다.** 이러면 1차 룰은 LLM 콜을 실제로 0 으로 만드는 케이스(인사·감사·명시적 생성)에만 발화 → DoD 측정 깔끔, 회귀 위험 최소.

---

## 6. 구현 산출물 (예정)

- `domain/llm/service/RulePreRouter.java` (신규) — `route(message, history) → RuleDecision`
- `RuleDecision` (record): `{ Type type, ExtractionResult result }`, Type = TERMINAL | NEEDS_KEYWORDS | MISS
- `KeywordExtractor.extract()` 수정 — 맨 앞에 RulePreRouter 호출, 분기
- `AnalyticsEventType` — INTENT_RULE_HIT / MISS 추가
- 룰 회귀 테스트 (JUnit) — ADR §4 표 + 함정 케이스

---

## 7. 다음 조각 (이 문서 범위 밖)

- ② 경량 LLM 분류기: Grok MISS 응답을 `IntentCode` 로 매핑 (현재 4 Action → 14 코드 확장)
- ③ 정적 전략 맵 연결: `IntentRouting.ROUTING` → `StepExecutor` 실행 (WorkflowService)
