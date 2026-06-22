# Metabase 대시보드 핸드오프 스펙 

> 작성: 2026-06 (트랙 B / SCRUM-95 사전작업 후속)
> 대상: 대시보드 담당자. **데이터 소스 = MySQL `analytics_events` 테이블.**
> 모든 SQL은 MySQL 8.4 + 컨테이너 dev DB(`drawe_db`)에서 실행 검증 완료.
>
> 📎 **짝 문서:** [`admin-log-guide.md`](./admin-log-guide.md) — 어드민 로그·지표·PII·알람 **프레임워크(원천)**.
> 본 문서는 그 지표들의 **검증된 Metabase SQL 구현 모음**으로, admin-log-guide §2·§7과 지표가 겹친다.
> 지표 정의·정책·탭 구성은 admin-log-guide가 원천이고, 여기는 "바로 붙일 SQL"만 담당(10종, 실측).
> admin-log-guide에 없는 추가 카드: `offer_generate`(생성 제안율), `has_image_input`(멀티모달 입력),
> avg_score 히스토그램, reference_count 분포, blocked_reason 분해.

## 0. 테이블 구조

```
analytics_events(id, created_at, event_type, payload JSON, session_id, trace_id, user_id)
```
- payload는 JSON → `payload->>'$.필드'`(문자 추출), `CAST(payload->>'$.x' AS UNSIGNED/DECIMAL)`(숫자).
- event_type: `chat_start · chat_success · chat_error · search_executed · search_blocked · decision_keep · decision_skip · intent_rule_hit · intent_rule_miss`
  (rule_hit/miss는 베타에서 룰 라우팅 타야 쌓임 — 현재 dev 데이터엔 0건일 수 있음)

---

## 1. 일별 사용량 (세션·활성유저)

```sql
SELECT DATE(created_at) AS day,
       COUNT(*)                 AS chat_starts,
       COUNT(DISTINCT user_id)  AS active_users
FROM analytics_events
WHERE event_type = 'chat_start'
GROUP BY DATE(created_at)
ORDER BY day;
```

## 2. 룰 적중률  — **S1 DoD ≥ 30%**

```sql
SELECT
  SUM(event_type = 'intent_rule_hit')  AS rule_hit,
  SUM(event_type = 'intent_rule_miss') AS rule_miss,
  ROUND(100 * SUM(event_type = 'intent_rule_hit')
        / NULLIF(SUM(event_type IN ('intent_rule_hit','intent_rule_miss')), 0), 1) AS rule_hit_rate_pct
FROM analytics_events
WHERE event_type IN ('intent_rule_hit','intent_rule_miss');
```

## 3. 의도 분포 (action / rule_id)

```sql
-- action 분포 (NEW_SEARCH / KEEP / SKIP / GENERATE_NOW)
SELECT payload->>'$.action' AS action, COUNT(*) AS n
FROM analytics_events
WHERE event_type = 'intent_rule_hit'
GROUP BY action ORDER BY n DESC;

-- 어떤 룰이 발동했나
SELECT payload->>'$.rule_id' AS rule_id, COUNT(*) AS n
FROM analytics_events
WHERE event_type = 'intent_rule_hit'
GROUP BY rule_id ORDER BY n DESC;
```

## 4. 검색 차단율 + 사유

```sql
-- 차단율
SELECT
  SUM(event_type = 'search_executed') AS executed,
  SUM(event_type = 'search_blocked')  AS blocked,
  ROUND(100 * SUM(event_type = 'search_blocked')
        / NULLIF(SUM(event_type IN ('search_executed','search_blocked')), 0), 1) AS blocked_rate_pct
FROM analytics_events
WHERE event_type IN ('search_executed','search_blocked');

-- 차단 사유 (low_score / exception)
SELECT payload->>'$.blocked_reason' AS reason, COUNT(*) AS n
FROM analytics_events
WHERE event_type = 'search_blocked'
GROUP BY reason ORDER BY n DESC;
```

## 5. 검색 점수 분포 (avg_score 히스토그램)

```sql
SELECT ROUND(CAST(payload->>'$.avg_score' AS DECIMAL(4,2)), 1) AS avg_score_bucket,
       COUNT(*) AS n
FROM analytics_events
WHERE event_type = 'search_executed'
GROUP BY avg_score_bucket
ORDER BY avg_score_bucket;
```

## 6. 참조 개수 분포 (0건 비율 = 검색 실패 체감)

```sql
SELECT CAST(payload->>'$.reference_count' AS UNSIGNED) AS ref_count,
       COUNT(*) AS n
FROM analytics_events
WHERE event_type = 'chat_success'
GROUP BY ref_count
ORDER BY ref_count;
```

## 7. 응답 latency (provider별 avg/max + p95)

```sql
-- avg / max
SELECT payload->>'$.provider' AS provider,
       COUNT(*) AS n,
       ROUND(AVG(CAST(payload->>'$.latency_ms' AS UNSIGNED))) AS avg_ms,
       MAX(CAST(payload->>'$.latency_ms' AS UNSIGNED))        AS max_ms
FROM analytics_events
WHERE event_type = 'chat_success'
GROUP BY provider;

-- p95 (MySQL 8 window 함수; PERCENTILE_CONT 미지원이라 PERCENT_RANK 사용)
WITH ranked AS (
  SELECT CAST(payload->>'$.latency_ms' AS UNSIGNED) AS ms,
         PERCENT_RANK() OVER (ORDER BY CAST(payload->>'$.latency_ms' AS UNSIGNED)) AS pr
  FROM analytics_events WHERE event_type = 'chat_success'
)
SELECT MIN(ms) AS p95_ms FROM ranked WHERE pr >= 0.95;
```

## 8. 에러율 + error_code 분포

```sql
-- 에러율 = chat_error / chat_start
SELECT
  SUM(event_type = 'chat_error') AS errors,
  SUM(event_type = 'chat_start') AS starts,
  ROUND(100 * SUM(event_type = 'chat_error')
        / NULLIF(SUM(event_type = 'chat_start'), 0), 1) AS error_rate_pct
FROM analytics_events
WHERE event_type IN ('chat_error','chat_start');

-- error_code 분포
SELECT payload->>'$.error_code' AS error_code, COUNT(*) AS n
FROM analytics_events
WHERE event_type = 'chat_error'
GROUP BY error_code ORDER BY n DESC;
```

## 9. 생성 제안율 (자료부족 → AI 생성 제안)

```sql
SELECT ROUND(100 * AVG(payload->>'$.offer_generate' = 'true'), 1) AS offer_generate_pct,
       COUNT(*) AS total
FROM analytics_events
WHERE event_type = 'chat_success';
```

## 10. 멀티모달(이미지 입력) 사용률

```sql
SELECT ROUND(100 * AVG(payload->>'$.has_image_input' = 'true'), 1) AS image_input_pct,
       COUNT(*) AS total
FROM analytics_events
WHERE event_type = 'chat_success';
```

---

## ⚠️ Metabase(SQL)로 안 되는 DoD — 별도 처리 필요

아래 지표는 `analytics_events`가 아니라 **Micrometer → Prometheus 실시간 메트릭**(`/actuator/prometheus`)에 있습니다. Metabase(SQL)로는 못 보니 **Grafana로 그리거나, 보고에 꼭 필요하면 `analytics_events`에도 적재하는 작업이 선행**돼야 합니다.

| 지표 | Prometheus 메트릭 | DoD |
|------|------------------|-----|
| 사전 적중률 | `drawe.dict.lookup{result=hit\|miss}` | **S1 ≥ 60%** |
| 분류 latency | `drawe.intent.classify` | **S1 ≤ 300ms** |
| 환각 인용 | `drawe.output.hallucinated_citation` | **S2 0건** |
| 구조 위반율 | `drawe.output.structure_violation` | **S2 ≤ 1%** |
| LLM 폴백 빈도 | `drawe.komoran.fallback` | 관측용 |

> intent precision(**S2 DoD ≥90%**)은 자동 메트릭이 아니라 **라벨링/수기 검증**이 필요(베타 로그 샘플 vs 실제 의도 비교).