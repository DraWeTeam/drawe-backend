# live 게이트 QA 전환 가이드 (WORKFLOW_COMPOSE_LIVE_INTENTS)

**작성:** 2026-06-18 / **브랜치:** `feature/SCRUM-96-AI-pipeline-A`
**목적:** COMPOSE 메인경로를 레거시 → live(WorkflowService 전체 워크플로)로 전환. QA 단계에서 전체 워크플로우를 일괄 검증하기 위해 **COMPOSE 종착 의도 전부**를 켠다.

---

## 0. 한 줄 요약

게이트는 코드가 아니라 **환경변수**(`WORKFLOW_COMPOSE_LIVE_INTENTS`)로 제어한다. 비우면 전부 레거시(기본), 의도 이름을 콤마로 나열하면 그 의도만 live. **언제든 비워서 즉시 롤백** 가능(재기동만 필요, 코드 배포 불필요).

## 1. 설정값 (QA = 전부 켜기)

```
WORKFLOW_COMPOSE_LIVE_INTENTS=NEW_SEARCH,KEEP,SKIP,FOLLOWUP,COMPARE,OUT_OF_DOMAIN,COMPOSITION,LIGHTING,COLOR,TECHNIQUE,SELF_CRITIQUE
```

- **11개 = COMPOSE 종착 의도 전부.** `IntentCode` enum 이름으로 바인딩.
- **GENERATE(008) 는 절대 넣지 말 것** — 라우팅이 `[TRANSLATE, GENERATE_IMAGE]`(COMPOSE 미종착)라 R1 부팅 가드가 `IllegalStateException` 으로 **부팅을 거부**한다. (애초에 그 Executor 들은 스텁이라 켜면 런타임 500.)
- **SELF_CRITIQUE(010) 주의:** COMPOSE 종착이라 부팅은 통과하나, 이미지 업로드(멀티모달 vision) 전제다. QA 에서 이미지 비평을 테스트하려면 운영 COMPOSE provider 가 vision 지원 모델인지 먼저 확인. (현재 기본 `grok-4-fast-non-reasoning` 의 vision 지원 미확정 — 텍스트 의도만 검증할 거면 이 항목은 빼도 됨.)

## 2. 로컬 docker 검증 결과 (2026-06-18, 선행 완료)

backend 재빌드 + 위 게이트 + `FASTAPI_URL=http://host.docker.internal:8000`(실검색) 로 멀티턴 smoke:

| 의도 | HTTP | referencesAction | workflow_live | 결과 |
|------|------|------------------|---------------|------|
| NEW_SEARCH | 200 | NEW_SEARCH | true | 실검색 7건, search_executed 발사 |
| KEEP(→001 미술의도) | 200 | KEEP | true | decision_keep, 단기메모리 재사용 |
| FOLLOWUP | 200 | FOLLOWUP | true | decision_followup, offer=false |
| COMPARE | 200 | COMPARE | true | decision_compare, offer=false |
| SKIP | 200 | SKIP | true | decision_skip |
| OUT_OF_DOMAIN | 200 | OUT_OF_DOMAIN | true | 거절 톤 |

- **전 의도 500 없음, 전부 live 경로(workflow_live=true).** R1 부팅 검증 통과(11개).
- 단기메모리(Redis previousReferences) 저장·재사용, DECISION/SEARCH analytics 발사 확인.
- 앱 ERROR 없음(유일한 ERROR 는 otel collector localhost:4318 미기동 — 운영엔 collector 있어 무관).

## 3. 배포(ECS) 적용 절차

CD(`cd.yml`)는 **기존 task definition 을 describe → 이미지 URI 만 교체 → register** 하므로 env 는 보존만 한다. 즉 게이트는 **task definition 의 환경변수에 직접 추가**해야 하며, 코드/git 으로는 안 된다. **AWS 권한 필요.**

### 방법 A — AWS 콘솔 (간단)
1. ECS → Task Definitions → `drawe-dev-backend`(또는 QA 환경명) 선택 → "Create new revision"
2. 컨테이너 정의 → Environment variables → 추가:
   - Key: `WORKFLOW_COMPOSE_LIVE_INTENTS`
   - Value: `NEW_SEARCH,KEEP,SKIP,FOLLOWUP,COMPARE,OUT_OF_DOMAIN,COMPOSITION,LIGHTING,COLOR,TECHNIQUE,SELF_CRITIQUE`
3. 새 리비전 저장 → ECS Service `drawe-dev-backend` 를 새 리비전으로 update (force new deployment).

### 방법 B — AWS CLI
```bash
ENV=dev   # QA 환경명
SVC=drawe-$ENV-backend
aws ecs describe-task-definition --task-definition $SVC --query 'taskDefinition' > td.json
# td.json 의 containerDefinitions[].environment 에 아래 항목 추가 (jq 예시):
jq '(.containerDefinitions[0].environment) += [{"name":"WORKFLOW_COMPOSE_LIVE_INTENTS","value":"NEW_SEARCH,KEEP,SKIP,FOLLOWUP,COMPARE,OUT_OF_DOMAIN,COMPOSITION,LIGHTING,COLOR,TECHNIQUE,SELF_CRITIQUE"}]
   | del(.taskDefinitionArn,.revision,.status,.requiresAttributes,.compatibilities,.registeredAt,.registeredBy)' \
   td.json > td-new.json
NEW_TD=$(aws ecs register-task-definition --cli-input-json file://td-new.json --query 'taskDefinition.taskDefinitionArn' --output text)
aws ecs update-service --cluster drawe-$ENV-cluster --service $SVC --task-definition $NEW_TD --force-new-deployment
```
> ⚠️ 이미 같은 key 가 있으면 `+= [...]` 대신 기존 값을 교체해야 한다(중복 시 ECS 가 거부). 위 jq 는 신규 추가 기준.

### 부팅 성공 확인 (적용 후)
새 task 로그에서:
```
workflow.compose live 의도(부팅 검증 통과): [OUT_OF_DOMAIN, COMPOSITION, ... , COMPARE]
Started DraweBackendApplication
```
이 두 줄이 나오면 정상. `IllegalStateException ... live-intents 설정 오류` 가 나오면 GENERATE 등 COMPOSE 미종착 의도가 섞인 것 → 값 점검.

## 4. 롤백

게이트 환경변수를 **비우고**(`WORKFLOW_COMPOSE_LIVE_INTENTS=` 또는 항목 제거) 새 리비전으로 service update → 전부 레거시 경로로 즉시 복귀. 코드 배포 불필요. 의도별로 일부만 빼는 것도 가능(예: NEW_SEARCH 만 제거).

## 5. QA 중 관측 포인트

- **`workflow_live=true`** (chat_success payload) — live 경로로 탔는지.
- **`decision_keep/skip/followup/compare`** analytics — 의도 분류·빈도.
- **`search_executed`** payload(avg/max/scores) — NEW_SEARCH 검색 품질·점수가드 차단율.
- ⚠️ **NEW_SEARCH 알려진 차이:** live 는 키워드를 Grok→**Komoran** 으로 뽑는다. 로컬 shadow 측정(n=4)에서 Grok 대비 **결과 겹침 10~30%(전부 partial, match 0)** 였다. 즉 QA 에서 NEW_SEARCH 레퍼런스가 레거시와 **꽤 다를 수 있다** — 이건 버그가 아니라 Komoran 사전(트랙 B, SCRUM-95) 성숙도 문제. QA 에서 "레퍼런스 품질이 쓸 만한가"를 사람이 직접 보는 게 이번 전환의 핵심 검증 포인트. 품질이 나쁘면 NEW_SEARCH 만 게이트에서 빼고 사전 개선 후 재시도.

## 6. 의도된 잔여 차이 (버그 아님)

- NEW_SEARCH 키워드 Grok→Komoran (위 §5).
- KEEP 의 단기메모리 refs 는 LLM 컨텍스트로만 쓰고 응답 references 엔 노출 안 함(설계대로 refCount=0).
