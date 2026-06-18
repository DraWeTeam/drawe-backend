# 013 COMPARE — docker e2e 실연동 검증 결과

**테스트 일시:** 2026-06-18 KST
**대상 브랜치:** `feature/SCRUM-96-AI-pipeline-A` (커밋 `04b2aba`, push 완료)
**대상 변경:** 013 COMPARE 도입 (012 FOLLOWUP 쌍둥이 — 이미 맥락에 있는 대상 비교, 검색·생성 없음)
**테스트 방식:** docker-compose 전체 기동(MySQL 8.4 / Valkey / backend 컨테이너, 013 코드 재빌드) + 실제 HTTP 호출. 분류기(Grok) 실연동. **양경로 검증** — 레거시(게이트 off) + live(게이트 `WORKFLOW_COMPOSE_LIVE_INTENTS=COMPARE`).

## 테스트 환경

| 항목 | 값 |
|------|-----|
| 앱 | `drawe-backend-1` 컨테이너 @8080 (2026-06-18 재빌드 = 013 반영) |
| DB / Redis | `drawe-drawe_db-1` MySQL 8.4 @3309, `drawe-drawe_redis-1` Valkey @6379 |
| 테스트 user / project | id=1 (test1@test.com) / id=2 (user_id=1 소유) |
| 인증 | JWT_SECRET(BASE64 디코딩) HS256, sub=1 직접 발급 (hmac 수동) |
| 분류기 | `grok-4-fast-non-reasoning` (Grok) — 013 은 history 의존이라 rule_miss→Grok 폴백 |
| 검색 | 외부 embedding/vector 호출 미연결(`WebClientRequestException`, SEARCH_FAILED) → references 항상 빔. COMPARE 의 빈-refs 가이드 검증엔 오히려 적합 |

## 검증 시나리오

COMPARE 는 이미 맥락에 있는 둘 이상의 대상에 의존하므로, **선행 턴**으로 ASSISTANT 가 두 옵션(수채화 vs 펜선)을 제시하게 한 뒤 같은 세션에서 비교 발화를 던졌다.

- 선행 턴: `"고양이를 수채화로 그릴까 펜선으로 그릴까 고민이야. 두 방식 특징 알려줘"` → 두 방식 비교 질문이라 그 자체로 **COMPARE** 분류. (session `af95dcf5…` / live `f0a7727d…`)

---

## ① 핵심 케이스 — 비교 발화 → COMPARE ✅ (레거시 경로)

| 입력 | referencesAction | offerGenerate | 본문 |
|------|------------------|---------------|------|
| `둘 중에 뭐가 더 나아?` | **COMPARE** | **false** | "수채화는 물감이 번지면서 따뜻하고 부드러운 봄 느낌… 펜선은 선이 뚜렷해서 형태가 또렷… 프로젝트가 ‘벚꽃 수채화’라서 수채화가 더 어울릴 것 같아요." |
| `수채화랑 펜선 차이가 뭐야?` | **COMPARE** | **false** | "수채화는 색이 부드럽게 스며들고 따뜻한 분위기… 펜선은 선이 명확해서 형태가 또렷하고 캐릭터 느낌…" |
| `장단점 비교해줘` | **COMPARE** | **false** | "수채화는 부드럽고 따뜻하지만 건조 시간이 길고 세밀한 선이 어려워요. 펜선은 또렷하고 빠르지만 색감이 단조로워…" |

로그 증거 체인 (세 케이스 동일):
```
INFO intent_rule_miss ... (룰 미스 → Grok 폴백; 013은 history 의존이라 정상)
INFO 🔍 COMPARE — 맥락 대상 비교 (session=af95dcf5…)
INFO event_type=decision_compare ... payload={message_length=...}
INFO event_type=chat_success ... offer_generate=false, reference_count=0
```

| 구분 | 내용 |
|------|------|
| **기대값** | `referencesAction=COMPARE`, `offerGenerate=false`, 두 대상을 짚어 차이·장단점 비교. 'AI 생성 권유' 미발생 |
| **결과값** | 정확히 일치. 본문이 실제로 두 방식을 대조하고 프로젝트 맥락(‘벚꽃 수채화’)까지 반영 |
| **차이** | 없음 |

---

## ② 회귀 대조 — 기존 분기 안 깨짐 ✅

| 입력 | 기대 | referencesAction | offerGenerate | 판정 |
|------|------|------------------|---------------|------|
| `수채화 어때?` (단일 대상) | COMPARE 아님 | **FOLLOWUP** | false | ✅ 단일 대상은 COMPARE 로 안 잡힘 — 정확히 구분 |
| `고마워` | SKIP | **SKIP** | false | ✅ 회귀 없음 |
| `비슷한 고양이 레퍼런스 더 보여줘` | NEW_SEARCH | **NEW_SEARCH** | true* | ✅ 새 이미지 요청과 구분 |

\* `더 보여줘`의 offerGenerate=true 는 검색 결과가 비어서(외부 검색 미연결) NEW_SEARCH 의 정상 동작.

핵심: COMPARE("둘 중 뭐가 나아")와 FOLLOWUP("어때?"·단일대상)·NEW_SEARCH("더 보여줘")가 분류기에서 명확히 갈린다.

---

## ③ live(COMPOSE) 경로 — 012 §B 결함 미재현 ✅

게이트 `WORKFLOW_COMPOSE_LIVE_INTENTS=COMPARE` ON 으로 재기동 후 동일 시나리오 재실행.

| 입력 | referencesAction | offerGenerate | workflow_live |
|------|------------------|---------------|---------------|
| `둘 중에 뭐가 더 나아?` | **COMPARE** | **false** | **true** |
| `수채화랑 펜선 차이가 뭐야?` | **COMPARE** | **false** | **true** |
| `장단점 비교해줘` | **COMPARE** | **false** | **true** |

로그 증거:
```
WARN ⚙️ COMPOSE live 경로: code=013 session=f0a7727d… — 레거시 점수가드·검색 analytics 미재현…
INFO event_type=chat_success ... workflow_live=true, offer_generate=false, reference_count=0
```

| 구분 | 내용 |
|------|------|
| **기대값** | live 경로에서도 COMPARE 가 'AI 생성 권유' 없이 비교 설명. workflow_live=true, offerGenerate=false |
| **결과값** | 정확히 일치. **012 §B 에서 발견됐던 결함(live 경로 빈-refs 'AI 생성 권유' 재현)이 013 에는 없음** |
| **차이** | 없음 |

> 012 의 교훈("새 의도 도입 시 레거시·live 양경로 모두 빈-references 가이드 주입 점검")을 013 구현 시점에 선제 반영(`ComposeExecutor.buildReferenceContext` 에 `code==COMPARE` 분기 추가)해, live 1차 실행부터 결함 없이 통과했다.

---

## ④ analytics 영속 확인 ✅

레거시 세션 `af95dcf5…` 의 `analytics_events` (MySQL 직접 조회):
```
decision_compare 4
```
→ COMPARE 분류 4건(선행 + 핵심 3) 영속. 013 빈도가 운영에서 관측 가능함을 실증. (live 경로는 chat_success 의 workflow_live=true 로 집계.)

---

## 관찰 / 범위 밖

- **본문 품질:** 비교 응답이 실제로 두 방식의 장단점을 대조하고 프로젝트 맥락까지 반영 — 012 때보다 자연스러움. (검색이 막혀 [1][2] 실인용은 없으나 COMPARE 는 빈-refs 가이드로 말 비교를 유도하는 구조라 의도대로 동작.)
- **검색 미연결:** 로컬 환경 외부 embedding/vector 미연결로 references 항상 빔(`SEARCH_FAILED`). 실레퍼런스 [1] vs [2] 인용 비교는 prod/staging 데이터셋에서 후속 관측 대상. 단 COMPARE 의 검증 포인트(분류 + 빈-refs '비교 안내' 가이드 + 생성권유 금지)는 충족.
- **UTF-8(테스트 함정):** Git Bash 콘솔 출력만 한글 깨짐(코드페이지) — 실제 요청/응답/로그/DB 는 정상. JSON 은 `ensure_ascii=False` 파일 + `--data-binary @file` + charset=utf-8 로 송신.

## 결론

013 COMPARE 가 **레거시·live 양경로** 실연동에서 의도대로 동작함을 확인.
- "둘 중 뭐가 나아"/"차이가 뭐야"/"장단점 비교해줘" → **COMPARE 분류 + offerGenerate=false + 두 대상 비교 설명**.
- 단일 대상("어때?")→FOLLOWUP, "고마워"→SKIP, "더 보여줘"→NEW_SEARCH 기존 분기 **무회귀**.
- live 경로에서 **012 §B 결함(빈-refs 'AI 생성 권유') 미재현** — 구현 시점에 선제 반영한 결과.
- `decision_compare` analytics DB 영속 확인.
- 검증 후 게이트 `WORKFLOW_COMPOSE_LIVE_INTENTS=` 비움으로 **원복**(운영 기본 off).
