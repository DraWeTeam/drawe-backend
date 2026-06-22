# S1' 공통 — Micrometer 메트릭 태깅 설계

> 작성일: 2026-06-08
> 짝 문서: [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) (ADR §4 메트릭 표, §8 도구)
> 성격: 구현 설계 노트.

---

## 0. 목표 · 배경

ADR §8: "MetricsCollector 자체 신설 기각 → **Micrometer + analytics_events 확장**. Timer/Counter 에 intent code·tier 태깅." 현재 코드에 Micrometer 직접 사용처는 **전무**(actuator/prometheus 의존성·엔드포인트만 존재).

기존 `analytics_events`(DB) 는 사후 SQL 분석용이고, Micrometer 는 **실시간 메트릭**(prometheus 스크랩 → 대시보드)이다. 둘은 보완 관계 — analytics 는 유지하고 Micrometer 를 **추가**한다(이중 집계 아님, 용도 다름).

ADR §4 메트릭 표에서 **지금 측정 가능한 것**(이미 구현된 동작)부터:

| 지표 | 목표 | 출처 |
|------|------|------|
| Intent rule coverage (룰 흡수율) | ≥ 30% | `RulePreRouter` 히트/미스 → Counter |
| 경량 LLM 분류 latency | ≤ 300ms | Grok 분류 호출 → Timer |
| 메인 LLM 콜 latency/성공률 | (관측) | `ChatLlmService` LLM 호출 → Timer (tag: provider) |

> intent precision(≥90%)·사전 적중률은 베타 라벨링/트랙 B 의존이라 이번 범위 밖.

---

## 1. 설계 원칙

1. **태그 카디널리티 통제** — Micrometer 태그는 시계열을 곱한다. 고카디널리티 값(userId, sessionId, 원문 메시지) 금지. 허용: `rule_id`(유한), `action`(4종), `provider`(3종), `result`(success/error), `hit`(true/false).
2. **얇은 래퍼** — 비즈니스 로직에 `MeterRegistry` 를 직접 흩뿌리지 않고, 측정 지점을 명시적 헬퍼로 모은다(`LlmMetrics` 컴포넌트). 테스트·변경 용이.
3. **analytics 와 공존** — 기존 `analyticsEventService.track(...)` 호출은 그대로. 같은 지점에서 Micrometer 도 기록.
4. **PII 0** — 메트릭엔 길이·코드·열거값만. (로그 정책과 동일 정신.)

---

## 2. 메트릭 정의

| 이름 | 타입 | 태그 | 의미 |
|------|------|------|------|
| `drawe.intent.route` | Counter | `outcome`={rule_hit, rule_miss} | 룰 흡수율 분모/분자. coverage = rule_hit/(hit+miss) |
| `drawe.intent.rule` | Counter | `rule_id`, `action` | 어떤 룰이 어떤 Action 으로 발화했나 (rule_hit 세부) |
| `drawe.llm.call` | Timer | `provider`, `outcome`={success,error} | 메인 LLM 호출 latency·성공률 |
| `drawe.intent.classify` | Timer | `outcome` | 경량 분류기(Grok) latency — DoD ≤300ms 측정 |

> coverage 는 별도 Gauge 로 만들지 않는다 — prometheus 쿼리 `rate(...rule_hit)/rate(...total)` 로 유도. 메트릭은 raw count 만.

---

## 3. 적용 지점

- **`RulePreRouter` 히트/미스**: 현재 `ChatLlmService.routeIntent()` 가 룰 히트/미스를 analytics 로 집계 중. **같은 자리**에 `drawe.intent.route` + `drawe.intent.rule` Counter 증가.
- **경량 분류 latency**: `routeIntent()` 의 미스 분기에서 `keywordExtractor.extract()` 호출을 Timer 로 감싼다 → `drawe.intent.classify`.
- **메인 LLM latency**: `ChatLlmService.chat()` 의 `llm.generate(ctx)` try-catch (이미 `result.latencyMs()` 측정·analytics 기록 중). 같은 자리에서 `drawe.llm.call` Timer 기록(success/error 분기).

---

## 4. 산출물 (예정)

- `domain/llm/metrics/LlmMetrics.java` (신규) — MeterRegistry 주입, 위 4개 메트릭 기록 메서드. 얇은 래퍼.
- `ChatLlmService` — `routeIntent()`/`chat()` 측정 지점에 LlmMetrics 호출 추가 (analytics 와 나란히).
- 검증: 단위 테스트(SimpleMeterRegistry 로 카운터/타이머 증가 확인) + `/actuator/prometheus` 에 `drawe_intent_*`, `drawe_llm_*` 노출 확인.

---

## 5. 미해결 (착수 전 확인)

1. `routeIntent()` 가 LLM 분류 latency 를 잴 때, Grok 호출은 `keywordExtractor.extract()` 내부라 호출 시간 전체를 Timer 로 감싸면 된다(메서드 외부에서 측정). 별도 계측 주입 불필요.
2. 동의 안 한 사용자 제외(ADR §2): analytics 는 그 로직이 있는지 확인 필요. Micrometer 는 집계 메트릭이라 사용자 단위 제외가 어려움 — 1차는 전체 집계(메트릭엔 PII 없으니 동의 이슈 약함), 필요 시 트랙 B 에서 재검토.
