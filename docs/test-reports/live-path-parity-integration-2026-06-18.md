# live 경로 레거시 동등성 갭 통합 + e2e 재실증 (SCRUM-96 ← SCRUM-87)

**일시:** 2026-06-18 KST
**브랜치:** `feature/SCRUM-96-AI-pipeline-A`
**배경:** live 게이트 운영 ON 가능 여부를 판단하던 중, live 경로(`chatViaWorkflow`)가 레거시 대비 **단기메모리·DECISION analytics 를 빠뜨린 상태**임을 코드 실측으로 확인. 그 갭을 메운 작업(`b61c6cf`)이 `feature/SCRUM-87-AI-pipeline-A` 에 고립돼 있어(우리 브랜치는 그 1커밋 직전에서 분기) cherry-pick 으로 통합.

## 통합 내역 (cherry-pick `b61c6cf` → 충돌 3파일 수동 해소)

| 파일 | 충돌 원인 | 해소 방침 |
|------|----------|----------|
| `SearchExecutor.java` | b61c6cf 의 `blocked` 식(OR, 0건가드 제거) vs 우리 D점수가드(AND rescue) 가 같은 줄 | **둘 다 반영**: 0건가드 제거(b61c6cf) + AND rescue(우리) → `blocked = avg<0.2 && max<0.24`. 0건이면 avg=max=0 → AND 에서도 차단 유지 |
| `CritiqueUploadExecutor.java` | b61c6cf 는 스텁 로그만 수정 / 우리는 010 a2 실구현으로 교체 | **우리(실구현) 채택**, b61c6cf 스텁 변경 폐기 |
| `SearchExecutorTest.java` | 양쪽이 같은 위치에 다른 테스트 추가 | **4개 모두 보존**: rescue 2(우리) + 0건차단·예외 2(b61c6cf) |

### 자동머지 회귀 수정 (수동 보정)
b61c6cf 시점엔 012/013 이 없어, 자동머지가 `ChatLlmService` 의 두 메서드를 옛 버전으로 덮어써 회귀 발생 → 복구:
- `referencesAction`: switch 로 복구(`FOLLOWUP`/`COMPARE`/`OUT_OF_DOMAIN`/`SELF_CRITIQUE` 케이스). 자동머지가 만든 **중복 메서드 정의 1건 제거**.
- `emitDecisionAnalytics`(live 경로용): `case FOLLOWUP→DECISION_FOLLOWUP`, `case COMPARE→DECISION_COMPARE` 추가(b61c6cf 는 KEEP/SKIP 만 → 012/013 이 KEEP 으로 오집계되던 것 차단).

## 검증

### 단위 테스트 (JDK23)
- 컴파일(main+test) 성공.
- SearchExecutorTest **12** / ComposeExecutorTest / WorkflowComposePropertiesTest / IntentResultAdapterTest / WorkflowServiceTest / RulePreRouterTest 전원 통과.
- SearchExecutorTest 4개 가드 테스트(rescue·avg통과·0건차단·예외) 공존 통과.

### docker e2e — live 게이트 ON (NEW_SEARCH,KEEP,SKIP,FOLLOWUP,COMPARE)
backend 재빌드(cherry-pick 반영) + `FASTAPI_URL=host.docker.internal:8000` 주입으로 **실검색 연동**. 호스트 CLIP FastAPI + Pinecone 정상.

| 턴 | referencesAction | refCount | workflow_live | analytics |
|----|------------------|----------|---------------|-----------|
| `강아지 레퍼런스 보여줘` | NEW_SEARCH | **7** (실검색!) | true | `search_executed` (avg=0.247, max=0.255, 7건) |
| `그 중에 첫번째 어떻게 그려?` | KEEP | 0* | true | `decision_keep` |
| `고마워` | SKIP | 0 | true | `decision_skip` |

\* KEEP 의 refCount=0 은 설계대로 — 직전 refs 를 **LLM 컨텍스트로만** 재사용하고 응답엔 노출 안 함.

### 갭 종료 실증
- ✅ **단기메모리**: Redis `session:1:2` 에 `previousReferences` **7개 저장**(키: userId/projectId/previousReferences/lastIntent/lastKeywords/lastUpdatedAt). NEW_SEARCH 결과가 저장돼 다음 KEEP 턴이 `getOrRestore` 로 복원. KEEP 턴이 검색 없이(=search_executed 없음) 진행됨.
- ✅ **DECISION analytics(live)**: `decision_keep`·`decision_skip` 발사 확인(이전엔 live 경로에서 누락되던 핵심 갭).
- ✅ **SEARCH analytics + 점수가드**: `search_executed` payload(avg/max/min/scores/image_ids) 발사.
- ✅ **거짓 WARN 교정**: 로그가 `"점수가드·검색/결정 analytics 재현됨, 남은 차이는 키워드 Grok→Komoran(shadow outcome 확인 후 켤 것)"` 로 갱신(이전 `"미재현·⑦에서 닫음"` 거짓 문구 제거).
- ✅ **R1 부팅 가드**: `live 의도(부팅 검증 통과): [NEW_SEARCH, KEEP, SKIP, FOLLOWUP, COMPARE]` — 전부 COMPOSE 종착이라 통과.

검증 후 게이트·FASTAPI_URL **원복**(운영 기본 off).

## 결론 — live 게이트 운영 ON 판단

통합 전 NO-GO 사유였던 2대 갭(단기메모리·DECISION analytics)이 **닫혔고 e2e 로 입증**됐다. 남은 의도된 차이는 **키워드 추출 Grok→Komoran** 하나뿐(shadow 경로로 관리, ⑦ 범위).

**ON 가능 상태가 됐다.** 다만 설계(§3.3) 원칙대로 **shadow outcome 이 match 인 의도부터 점진 전환**해야 하며, 그 shadow match 데이터를 운영에서 확인하는 게 ON 의 선결조건이다. 즉 "코드는 ON 가능, 실제 ON 은 shadow 관측 후"가 현재 권고.
