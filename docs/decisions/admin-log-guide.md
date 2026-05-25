# 어드민 로그·모니터링 가이드

> 작성일: 2026-05-21
> 대상: 운영자·PM·QA — 배포 후 무엇을 보고 무엇을 알 수 있는지

배포 후 어드민에서 "사용자가 잘 쓰고 있나? 어디서 막히나? 비용은 얼마 드나?" 를 답할 수 있어야 한다.
이 문서는 **어디에 무슨 데이터가 쌓이는지 → 어떤 질문에 답할 수 있는지 → 어떻게 보는지** 순서로 정리한다.

---

## 1. 데이터 소스 4개

| 저장소 | 무엇 | 용도 | 보존 |
|---|---|---|---|
| **`analytics_events` 테이블** | 사용자 행동 funnel (chat_start, search_executed, chat_error 등) | **메인 어드민 대시보드 소스**. 깔대기·전환율·에러율 계산 | 장기 |
| **`search_logs` 테이블** | 검색 1건당 raw 데이터 (원문 메시지, 키워드, 점수, 결과 수) | 검색 품질 분석 — "왜 이 검색이 안 잡혔나" 디버깅 | 장기 |
| **`prompt_translation_logs` 테이블** | AI 이미지 생성 시 한→영 번역 raw (ko_prompt, en_prompt, status) | 번역 품질 개선, Bria 호출 실패 추적 | 장기 |
| **애플리케이션 로그 (CloudWatch/stdout)** | INFO/WARN/ERROR 텍스트 로그 | **실시간 모니터링·온콜·인시던트 대응** | 단기 (30일) |

> 4개 모두 같은 사용자 동작이 4가지 다른 단면으로 기록됨. 어드민은 주로 1번을 보고, 디버깅할 때 2·3·4번을 본다.

---

## 2. 어드민 대시보드에서 답해야 할 질문 (우선순위)

### A. 서비스 건강성 (매일 보는 지표)

| 질문 | 보는 곳 | 쿼리 / 지표 |
|---|---|---|
| 오늘 DAU/MAU | `analytics_events` `event_type='chat_start'` | `COUNT(DISTINCT user_id)` 시간 범위별 |
| 채팅 응답 성공률 | `analytics_events` | `chat_success / (chat_success + chat_error)` |
| 평균 응답 지연 (P50/P95) | `analytics_events.payload.latency_ms` (chat_success) | 백분위수 계산 |
| 에러 발생 추이 | `analytics_events` `event_type='chat_error'` | 시간대별 카운트 + `payload.error_class` 그룹핑 |

### B. 기능 사용 패턴

| 질문 | 보는 곳 | 지표 |
|---|---|---|
| 사용자들이 검색 vs 즉시생성 중 뭘 더 많이 쓰나 | `analytics_events` event_type 분포 | NEW_SEARCH vs GENERATE_NOW 비율 |
| 검색 성공률 (관련 결과 잡힌 비율) | `analytics_events` | `search_executed / (search_executed + search_blocked)` |
| 사용자가 가장 많이 검색한 키워드 | `analytics_events.payload.keyword` 또는 `search_logs.extracted_keywords` | 키워드 빈도 집계 → 시드 데이터 보강 우선순위 |
| AI 이미지 생성 사용량 | `images` 테이블 + `created_by_user_id` | `WHERE source='AI' GROUP BY DATE(...)` |

### C. 비용·리스크

| 질문 | 보는 곳 | 지표 |
|---|---|---|
| LLM 호출 비용 추정 | `analytics_events.payload.provider, model, response_length` | provider/model별 호출 수 × 토큰 단가 |
| Bria 호출 횟수 (AI 이미지 생성) | `images` 테이블 `source='AI'` | 일별 카운트 (Bria 청구와 대조) |
| 응답 지연 폭증 알람 | `analytics_events.payload.latency_ms` p95 | 임계치(예: 10초) 초과 시 알람 |
| 검색 차단 비율 급증 | `search_blocked / search_executed` 추이 | 평소보다 높으면 시드 데이터 부족 신호 |

### D. 품질·개선 백로그

| 질문 | 보는 곳 | 어떻게 |
|---|---|---|
| 어떤 키워드가 자주 검색되는데 결과가 부족한가 | `search_logs` `result_count=0 OR avg_score<0.2` | 키워드 그룹핑 → 시드 보강 백로그 |
| 번역 실패가 자주 나는 입력 패턴 | `prompt_translation_logs` `status='FAILED' OR 'FALLBACK_RAW'` | ko_prompt 그룹핑 |
| 어떤 분류(KEEP/SKIP/NEW_SEARCH/GENERATE_NOW)가 가장 많나 | `analytics_events` `event_type IN ('decision_*', 'search_*')` | 비율 변화 추적 → KeywordExtractor 정확도 |

---

## 3. 어드민 화면 권장 구성 (4개 탭)

### 탭 1 — Overview (한눈에 보는 대시보드)
- 오늘 DAU, 응답 성공률, P95 지연, 에러 수 — 4개 큰 숫자
- 최근 24시간 시계열 차트: chat_success vs chat_error
- 최근 24시간 시계열 차트: search_executed vs search_blocked

### 탭 2 — Users (사용자별 활동)
- user_id 검색 → 그 사용자의 최근 이벤트 타임라인
- 컬럼: 시각, event_type, session_id (클릭하면 세션 상세), payload 요약
- **PII 노출 X** — `users` 테이블 join도 닉네임/이메일까지만, 채팅 원문은 절대 X

### 탭 3 — Search Quality (검색 품질 분석)
- `search_logs` 기반
- 차트: 일별 평균 result_count, 평균 avg_score
- 표: 최근 차단된 검색 (`result_count=0` 또는 `avg_score<0.2`) — 키워드별 빈도
- → 시드 데이터 보강 백로그 도출

### 탭 4 — Errors (에러 추적)
- `analytics_events` `event_type IN ('chat_error', 'search_blocked')`
- error_class별 그룹핑 + 최근 발생 시각
- 빈도 높은 에러는 상세 클릭 → session_id로 CloudWatch 로그 점프 가능하게

---

## 4. 실시간 모니터링 (CloudWatch — 어드민 화면과 별도)

어드민은 사후 분석. 실시간은 CloudWatch 로 본다.

### 알람 권장 셋
1. **에러율 알람**: `chat_error` 5분간 10건 이상 → 슬랙
2. **응답 지연 알람**: chat_success의 latency_ms p95 > 10000ms → 슬랙
3. **검색 차단 비율 알람**: `search_blocked / search_executed` > 50% (1시간 윈도우) → 슬랙
4. **백엔드 다운**: `/actuator/health` status != UP → 즉시 페이지

### 로그 검색 (CloudWatch Insights 예시)

```sql
-- 최근 1시간 에러 로그
filter @message like /LLM 호출 실패/
| sort @timestamp desc
| limit 50

-- 특정 사용자의 활동
filter user_id = 42
| sort @timestamp desc

-- 응답 지연 분포
filter @message like /CHAT_SUCCESS/
| stats avg(latency_ms), pct(latency_ms, 95), max(latency_ms) by bin(5m)
```

---

## 5. PII 주의 — 어드민에 절대 노출하면 안 되는 것

| 데이터 | 이유 | 대안 |
|---|---|---|
| 채팅 원문 (사용자가 친 한국어) | 사적 정보·서비스 외 발화 포함 가능 | 길이·키워드만 노출 |
| `prompt_translation_logs.ko_prompt` | 사용자 원문 그대로 저장됨 | 통계 집계용으로만, 개별 행은 PM·연구원만 접근 |
| `search_logs.original_message` | 위와 동일 | 위와 동일 |
| 이메일·전화번호 | 명백 PII | 어드민 화면에선 마스킹 (`d***@gmail.com`) |
| JWT, API 키, 비밀번호 해시 | 보안 자체 위험 | 로그·어드민 어디서도 절대 X |

> 백엔드 코드는 사용자 원문·키워드·영문 프롬프트·LLM raw 응답이 모두 stdout 로그로 안 나가도록 정리됨
> (PromptTranslator, ImageGenerationService, KeywordExtractor, ChatLlmService, BriaClient, Gemini/Grok/ClaudeService).
> 다만 `search_logs.original_message`, `search_logs.extracted_keywords`, `prompt_translation_logs.ko_prompt`,
> `prompt_translation_logs.en_prompt` 는 **DB에 그대로 저장**되므로 (어드민 검색 품질 분석·시드 보강 백로그용),
> 어드민 화면에 띄울 때는 정책상 마스킹·접근 권한 분리가 필요.

### 로그 정책 요약 (이후 새 로그 추가 시 참고)

| 데이터 종류 | 로깅 정책 |
|---|---|
| 사용자 원문 (한국어 입력) | ❌ 절대 안 됨 → length만 |
| LLM 원시 출력 (raw response) | ❌ 사용자 흔적 포함 가능 → length만 |
| 추출된 키워드 / 영문 프롬프트 | ❌ 사용자 의도 추적 가능 → length만 (DB는 유지) |
| user_id, session_id, image_id, source_id | ✅ 그대로 OK |
| 점수·통계·길이·횟수 등 수치 | ✅ 그대로 OK |
| 외부 API URL, request_id, error_class | ✅ 그대로 OK |

---

## 6. 구현 우선순위 추천

배포 직후 한 번에 다 만들 필요는 없음. 순서:

| 단계 | 만들 것 | 왜 먼저 |
|---|---|---|
| **P0 (배포 즉시)** | CloudWatch 알람 4종 (에러율·지연·차단율·헬스) | 사용자보다 먼저 문제 알아야 함 |
| **P1 (1주차)** | 어드민 탭 1 — Overview | DAU·성공률만 봐도 일상 운영 가능 |
| **P2 (2~3주차)** | 어드민 탭 3 — Search Quality | 시드 데이터 보강 백로그 채우기 시작 |
| **P3 (필요시)** | 어드민 탭 4 — Errors | 에러가 늘면 그때 |
| **P4 (분석 needs 생기면)** | 어드민 탭 2 — Users | 개별 사용자 추적이 필요해질 때 |

---

## 7. 빠른 시작 — 지금 당장 볼 수 있는 SQL 3개

도커 MySQL이든 RDS든 같은 쿼리.

### 오늘 활동 요약
```sql
SELECT event_type,
       COUNT(*) AS cnt,
       COUNT(DISTINCT user_id) AS unique_users
FROM analytics_events
WHERE created_at >= CURDATE()
GROUP BY event_type
ORDER BY cnt DESC;
```

### 최근 차단된 검색 Top 20 (시드 보강 후보)
```sql
SELECT extracted_keywords,
       COUNT(*) AS blocked_count,
       AVG(avg_score) AS avg_score_when_blocked
FROM search_logs
WHERE created_at >= NOW() - INTERVAL 7 DAY
  AND (result_count = 0 OR avg_score < 0.2)
GROUP BY extracted_keywords
ORDER BY blocked_count DESC
LIMIT 20;
```

### 최근 에러 Top 10
```sql
SELECT JSON_EXTRACT(payload, '$.error_class') AS error_class,
       COUNT(*) AS cnt,
       MAX(created_at) AS last_occurred
FROM analytics_events
WHERE event_type = 'chat_error'
  AND created_at >= NOW() - INTERVAL 24 HOUR
GROUP BY error_class
ORDER BY cnt DESC
LIMIT 10;
```

이 3개만 매일 한 번씩 돌려도 운영 거의 다 됨.
