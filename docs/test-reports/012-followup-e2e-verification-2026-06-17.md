# 012 FOLLOWUP — docker e2e 실연동 검증 결과

**테스트 일시:** 2026-06-17 KST
**대상 브랜치:** `feature/SCRUM-96-AI-pipeline-A`
**대상 변경:** 012 FOLLOWUP 도입 (KeywordExtractor 4→5 Action 정면 확장, 미push)
**테스트 방식:** docker-compose 전체 기동(MySQL 8.4 / Valkey / backend 컨테이너) + 실제 HTTP 호출. 분류기(Grok) 실연동. 게이트 `WORKFLOW_COMPOSE_LIVE_INTENTS=`(비움) = **레거시 경로** 검증.

## 테스트 환경

| 항목 | 값 |
|------|-----|
| 앱 | `drawe-backend-1` 컨테이너 @8080 (변경 반영 재빌드) |
| DB / Redis | `drawe-drawe_db-1` MySQL 8.4 @3309, `drawe-drawe_redis-1` Valkey @6379 |
| 테스트 user / project | id=1 (test1@test.com) / id=2 (user_id=1 소유) |
| 인증 | JWT_SECRET(BASE64 디코딩) HS256, sub=1 직접 발급 |
| 분류기 | `grok-4-fast-non-reasoning` (Grok) — 012 는 history 의존이라 rule_miss→Grok 폴백 |
| 게이트 | off (레거시 경로). 012 부연 톤은 게이트와 무관하게 레거시에서 즉시 발현 |

## 검증 시나리오

FOLLOWUP 은 직전 ASSISTANT 답변 맥락에 의존하므로, **선행 턴**으로 답변을 만든 뒤 같은 세션에서 후속질문을 던졌다.

- 선행 턴: `"인물 전신 구도 어떻게 잡으면 좋을까?"` → HTTP 200, `referencesAction=KEEP`, ASSISTANT 가 전신 구도 조언. (session `8a173830…`)

---

## ① 핵심 케이스 — "더 설명" / "말로 설명" → FOLLOWUP ✅

| 입력 | referencesAction | offerGenerate | 본문 |
|------|------------------|---------------|------|
| `더 설명` | **FOLLOWUP** | **false** | 직전 답변(전신 구도) 이어서 부연 — "캔버스 아래쪽에 놓고 diagonal pose로…" |
| `말로 설명` | **FOLLOWUP** | **false** | "인물 전신을 캔버스 아래쪽에 놓으면 상단에 자유로운 공간이 생겨서…" |

로그 증거 체인 (두 케이스 동일):
```
INFO intent_rule_miss ... (룰 미스 → Grok 폴백; 012는 history 의존이라 정상)
INFO 💬 FOLLOWUP — 직전 답변 부연 (session=8a173830…)
INFO event_type=decision_followup ... payload={message_length=4}
INFO event_type=chat_success ... offer_generate=false, reference_count=0
```

| 구분 | 내용 |
|------|------|
| **기대값** | `referencesAction=FOLLOWUP`, `offerGenerate=false`, 직전 답변을 이어서 설명. 'AI 생성 권유' 미발생 |
| **결과값** | 정확히 일치. 로그에 `💬 FOLLOWUP` + `decision_followup` analytics |
| **차이** | 없음 |

---

## ② 베타 좌절 케이스 재현 — "말을 하라고" → FOLLOWUP ✅

베타 세션 `7b57a6a3` 에서 "말!!!"/"말좀해"/"아니ㅜㅜ 말을 하라고" 좌절 연쇄의 트리거. 당시 분류기는 이를 못 알아듣고 **"자료가 좀 부족한 것 같아요. AI 이미지로 생성해드릴까요?"** 를 반복했다.

| 입력 | referencesAction | offerGenerate | 본문 |
|------|------------------|---------------|------|
| `말을 하라고` | **FOLLOWUP** | **false** | "인물 전신을 캔버스 아래쪽에 놓고 diagonal pose로…" (직전 답변 부연) |

| 구분 | 내용 |
|------|------|
| **기대값** | 후속질문으로 인식 → 직전 답변 부연. AI 생성 권유 오답 **미재현** |
| **결과값** | FOLLOWUP, offerGenerate=false, 말로 이어 설명. 베타 오답 패턴 해소 실증 |
| **차이** | 없음 |

---

## ③ 회귀 대조 — 기존 분기 안 깨짐 ✅

| 입력 | 기대 | referencesAction | offerGenerate | 판정 |
|------|------|------------------|---------------|------|
| `비슷한 전신 포즈 레퍼런스 더 보여줘` | NEW_SEARCH | **NEW_SEARCH** | true* | ✅ "더 설명"과 정확히 구분 |
| `고마워` | SKIP | **SKIP** | false | ✅ 회귀 없음 |
| `어때?` | FOLLOWUP | **FOLLOWUP** | false | ✅ 직전답변 평가 요청도 잡힘 |

\* `더 보여줘`의 offerGenerate=true 는 검색 결과가 비어서(베타 데이터셋 한계) NEW_SEARCH 의 정상 동작.

---

## ④ analytics 영속 확인 ✅

세션 `8a173830…` 의 `analytics_events` 집계 (MySQL 직접 조회):
```
chat_start 1 | chat_success 7 | decision_followup 4 | decision_keep 1
decision_skip 1 | intent_rule_hit 1 | intent_rule_miss 6 | search_blocked 1
```
→ `decision_followup` 4건 = (더 설명 + 말로 설명 + 어때? + 말을 하라고). 012 빈도가 운영에서 관측 가능함을 실증.

---

## 관찰 / 범위 밖

- **본문 품질 노이즈:** 일부 응답에 `<|eos|>`·`<fim-middle>`·영어/한자 혼입·깨진 글자. 이는 무료 플랜 **Grok 모델(grok-4-fast-non-reasoning) 출력 품질** 이슈로, FOLLOWUP **분류·라우팅과 무관**. 유료(Claude) 플랜에선 개선 예상.
- **live(COMPOSE) 경로 미검증:** 이번엔 게이트 off(레거시 경로)만 검증. `WORKFLOW_COMPOSE_LIVE_INTENTS=FOLLOWUP` 로 켠 live 경로는 `IntentRouting` 에 `FOLLOWUP→List.of(COMPOSE)` 등록을 마쳤으므로 동작해야 하나, e2e 실증은 후속 과제.
- **UTF-8 주의(테스트 함정):** Git Bash heredoc + curl 직접 한글은 `Invalid UTF-8 middle byte` 로 깨짐. JSON 을 `ensure_ascii=False` 로 파일에 쓰고 `--data-binary @file` + `charset=utf-8` 로 보내야 정상.

## 결론

012 FOLLOWUP 이 레거시 경로 실연동에서 의도대로 동작함을 확인.
- "더 설명"/"말로 설명"/"말을 하라고"/"어때?" → **FOLLOWUP 분류 + offerGenerate=false + 직전 답변 부연**.
- 베타 만족도 저하의 직접 원인이던 **'후속질문 → AI 생성 권유' 오답 패턴이 재현되지 않음**.
- "더 보여줘"(NEW_SEARCH)·"고마워"(SKIP) 기존 분기 **무회귀**.
- `decision_followup` analytics DB 영속 확인.

**다음 단계:** ~~live(COMPOSE) 경로 e2e~~(아래 §B 완료) · 013 COMPARE 도입 · D 점수가드 재설계 · 누적 미push 커밋 push 시점 결정.

---

# §B live(COMPOSE) 경로 e2e + ComposeExecutor 결함 발견·수정

**추가 검증일:** 2026-06-17 (같은 환경, 게이트 `WORKFLOW_COMPOSE_LIVE_INTENTS=FOLLOWUP` ON)

## ⓪ 결함 발견 — live 경로에서 베타 오답 재현 ❌→수정

게이트를 켜고 1차 live e2e 를 돌리자 **베타 오답이 그대로 재현**됐다.

| 입력 | referencesAction | offerGenerate | 본문 (수정 전) |
|------|------------------|---------------|------|
| `더 설명` | FOLLOWUP | **true** | **"자료가 좀 부족한 것 같아요. AI 이미지로 생성해드릴까요?"** ← 베타 오답 |

**원인:** live 경로(`chatViaWorkflow`→`ComposeExecutor`)는 레거시 경로의 `FOLLOWUP_GUIDE` 주입 코드보다 앞에서 분기한다. 그래서 012 가이드를 못 받고, `ComposeExecutor.buildReferenceContext` 가 references 가 비면(FOLLOWUP 은 항상 빔) **무조건 'AI 생성 권유' 안내**(`"자료가 좀 부족한 것 같아요. AI 이미지로 생성해드릴까요?"`)를 주입했다. 로그 `⚙️ COMPOSE live 경로: code=012` 로 live 진입은 확인됐으나 톤이 잘못됨.

**수정:** `ComposeExecutor.buildReferenceContext(refs, code)` 로 intent code 를 받아, `references 비었음 && code==FOLLOWUP` 이면 'AI 생성 권유' 대신 **'후속 질문 안내'**(직전 답변 부연, 생성 권유 금지 — 레거시 `FOLLOWUP_GUIDE` 와 동일 정신) 가이드를 반환. 단위테스트 추가(`followupWithoutReferences`): code=FOLLOWUP 이면 "후속 질문 안내" turn, 기본 "참고 이미지가 없습니다" 미포함. code=null 인 기존 빈-references 테스트는 무영향.

## ① 수정 후 재검증 — live 경로 FOLLOWUP ✅

| 입력 | referencesAction | offerGenerate | 본문 |
|------|------------------|---------------|------|
| `더 설명` | FOLLOWUP | **false** | 직전 답변(전신 구도) 이어서 — "중심에 놓고 배경을 균형 있게 배치…" |
| `말로 설명` | FOLLOWUP | **false** | 직전 답변 부연 |
| `말을 하라고` (베타 좌절 트리거) | FOLLOWUP | **false** | 직전 답변 부연 |

로그 증거 (FOLLOWUP 케이스):
```
INFO ⚙️ COMPOSE live 경로: code=012 session=0e79502b…
INFO event_type=chat_success ... workflow_live=true, offer_generate=false, reference_count=0
```
선행 턴(KEEP)은 `workflow_live` 없음 → 게이트가 **FOLLOWUP 에만** 작동(KEEP 은 레거시 경로) 실증.

| 구분 | 내용 |
|------|------|
| **기대값** | live 경로에서도 FOLLOWUP 이 'AI 생성 권유' 없이 직전 답변 부연. workflow_live=true, offerGenerate=false |
| **결과값** | 정확히 일치. 수정 전 재현되던 베타 오답이 **제거됨** |
| **차이** | 없음 (수정 후) |

## §B 결론

- live(COMPOSE) 경로 초기 상태는 **FOLLOWUP 에서 베타 오답을 재현**하는 결함이 있었고(레거시와 달리 가이드 미주입), `ComposeExecutor` 에 FOLLOWUP 분기를 추가해 닫았다.
- 수정 후 live 경로도 레거시 경로와 동일하게 **FOLLOWUP + offerGenerate=false + 직전 답변 부연** 으로 동작.
- 본문 품질 노이즈(`popularly`·`배 배 배…` 반복)는 무료 Grok 모델 이슈로 분류·가이드와 무관(레거시 경로에서도 동일).
- 검증 후 게이트 `WORKFLOW_COMPOSE_LIVE_INTENTS=` 비움으로 **원복**(운영 기본 off).
