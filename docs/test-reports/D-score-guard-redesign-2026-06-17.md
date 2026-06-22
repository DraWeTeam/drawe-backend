# D — 추천 점수가드 재설계 (베타 1순위 과제)

**작업일:** 2026-06-17
**대상 브랜치:** `feature/SCRUM-96-AI-pipeline-A` (미push)
**근거 데이터:** `drawe-beta-extract/search_scores.tsv` (베타 4명, 49건 검색 이벤트)
**선행 분석:** `beta-intent-frequency-and-score-tuning-2026-06-17.md` §D

## 배경

검색 점수가드 = "검색 결과가 무관하면 references 를 비우고 'AI 생성할까요?'로 넘기는" 장치.
- **현재:** `avg < 0.2 || max < 0.21` (OR) → 차단율 **29%** (14/49)
- 베타 분석에서 두 문제 확인:
  1. 차단의 거의 전부를 avg 조건이 담당. `max<0.21` 단독 차단은 1건뿐 = 잉여.
  2. 통과군 avg 하한이 0.201 로 floor 에 딱 붙어 **빡셌고**, "최상위 1장은 관련 있는데 평균에
     발목 잡혀 통째 차단"되는 케이스 존재(예 avg 0.189 / **max 0.255** — max 가 통과군 median
     0.254 보다 높음).
- 베타 만족도 1순위 불만 = "추천 관련성" → 가드를 **살짝 푸는** 방향이 맞다고 판단.

## 시뮬레이션 (search_scores 49건에 각 공식 적용)

| 공식 | 차단 | 차단율 | 통과 | 현재 대비 추가 구제 |
|------|:---:|:---:|:---:|------|
| **현재** OR `avg<0.20 \|\| max<0.21` | 14 | 29% | 35 | — |
| **채택** AND `avg<0.20 && max<0.24` | 12 | 24% | 37 | 2건 (man in suit, cat) |
| AND `avg<0.20 && max<0.23` | 11 | 22% | 38 | 3건 (+priest) |
| AND `avg<0.20 && max<0.22` | 10 | 20% | 39 | 4건 (+lighter) |
| OR `avg<0.20 \|\| max<0.18` | 13 | 27% | 36 | 1건 (cat) |
| avg만 `avg<0.20` | 13 | 27% | 36 | 1건 (cat) |

**구제되는 케이스 (현재 차단 → 채택 공식에서 통과):**
- `man in suit jacket full body` avg 0.189 / **max 0.255** ← 핵심. 최상위 1장이 통과군 median 보다 높음.
- `cat drawing reference` avg 0.202 / max 0.208 ← avg 가 floor 바로 위라 통과가 자연스러움.

## 채택: `avg < 0.2 && max < 0.24` (AND rescue)

**의미:** "평균도 낮고(avg<0.2) 최상위 1장도 별로(max<0.24)일 때만 차단. avg 가 낮아도 max≥0.24 면
최상위 레퍼런스는 관련 있다고 보고 살린다."

**선택 근거:**
- 과제 목적(추천 관련성)에 정확히 부합 — "상위 1~2장은 관련 있는데 통째 차단" 케이스(man in suit)를 구제.
- `max<0.22` 까지 풀면 priest(max 0.236)·lighter(max 0.222)도 통과하는데, 이들은 max 가 통과군
  median(0.254)보다 한참 낮아 "최상위도 그저 그런" 케이스 → 무관 노출 위험. 그래서 0.24 에서 멈춤.
- `avg만`(max 제거)은 man in suit(max 0.255)를 못 살림 — max 가 살리는 조건이 없어서. rescue 의 핵심을 놓침.
- ⚠️ n=49 **소표본** — 한 건 차이가 크게 보임. 단정 아닌 "방향(살짝 풀기)+타겟(상위장 구제)" 근거.

## 구현 (2파일, 두 경로 일관)

가드가 레거시·live 두 경로에 중복돼 있어 **둘 다** 변경(상수/리터럴 값만, 외부화는 안 함 — 사용자 결정):

- `SearchExecutor.java` (live/워크플로 경로): `MAX_SCORE_FLOOR` 0.21→**0.24**, blocked 조건 `||`→**`&&`**.
  주석·로그 문구 갱신.
- `ChatLlmService.java:604` (레거시 경로): `if (avgScore<0.2 || maxScore<0.21)` →
  `if (avgScore<0.2 && maxScore<0.24)`. 로그 문구 갱신.

## 테스트 (`SearchExecutorTest`)

- `scoreGuardBlocksLowScore` — avg·max 둘 다 낮음(0.10/0.12) → 차단 (DisplayName AND 로 갱신).
- `scoreGuardPassesHighScore` — score 0.5 → 통과 (유지).
- **신규** `scoreGuardRescuesHighMaxLowAvg` — 0.255/0.123 → avg 0.189(<0.2) 지만 max 0.255(≥0.24)
  → **통과**(rescue 핵심 검증, 베타 man in suit 모사).
- **신규** `scoreGuardPassesWhenAvgHighEvenIfMaxLow` — 0.21/0.20 → avg 0.205(≥0.2) → 통과
  (AND 라 avg 조건만 깨져도 통과).
- JDK23 컴파일 OK, SearchExecutorTest 전원 통과.

## 한계 / 후속

- **n=49 소표본.** 운영에서 SEARCH_EXECUTED/BLOCKED analytics 로 차단율 추이를 계속 관측해야 함.
  특히 차단율이 24% 아래로 더 내려가거나 무관 노출 불만이 늘면 max floor 재조정.
- live 경로 e2e 미실증(이번엔 시뮬+단위테스트). 실 검색 점수 분포가 베타와 다를 수 있어 운영 모니터링 필요.
- 외부화(properties)는 미적용 — 재조정 시 재배포 필요. 빈도 잦아지면 그때 외부화 검토.
