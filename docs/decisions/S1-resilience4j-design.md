# S1' 공통 — Resilience4j 외부 API 안정화 설계

> 작성일: 2026-06-08
> 짝 문서: [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) (ADR §8 도구, §11 운영 재확인, §12 후회 후보)
> 성격: 구현 설계 노트. 코드 착수 전 합의용.

---

## 0. 목표 · 배경

현재 모든 외부 API 호출이 **동기 블로킹**이고 **타임아웃이 없다**. 확인된 위험:

- `FastApiClient`, `PineconeClient` = WebClient `.block()` 인데 `.timeout()` 미설정 → 무한 대기 가능
- `BriaClient` = RestClient, factory 타임아웃 미설정
- 외부 하나가 hang 하면 톰캣 스레드가 묶이고 → 풀 고갈 → 무관한 기능(로그인·이미지 조회)까지 마비

ADR §12 "후회 후보 1순위"가 이것. 목표 = **외부 장애가 서비스 전체로 전파되지 않게 격리.**

---

## 1. 전략 = A (클라이언트 타임아웃 + 어노테이션, TimeLimiter 없이)

`@TimeLimiter` 는 `CompletableFuture`/`Mono` 반환에만 동작한다. 우리 클라이언트는 `.block()`/RestClient 동기라, 메서드 시그니처를 비동기로 바꾸면 호출 측(SearchService, ChatLlmService 등) 전부 영향 → 회귀 위험 큼.

**대신:**
1. **타임아웃 = 클라이언트 레벨**
   - WebClient: `.responseTimeout(Duration)` (reactor-netty) 또는 호출에 `.timeout(Duration)`
   - RestClient: `ClientHttpRequestFactory` connect/read 타임아웃
2. **서킷브레이커 · 재시도 = Resilience4j 어노테이션** (`@CircuitBreaker`, `@Retry`) — 동기 메서드에 그대로 붙음
3. **bulkhead** = 1차 보류. 타임아웃+서킷으로 전파 차단은 충분. 동시성 격리는 Phase 4 multi-call 도입 시 재검토 (ADR §8 표의 bulkhead 는 "러너업 후회 후보" 수준).

> 비동기 풀세트(전략 B)는 ADR Phase 4(multi-call 단계화)에서 진짜 비동기가 필요할 때 재검토.

---

## 2. 의존성

```groovy
implementation 'io.github.resilience4j:resilience4j-spring-boot3:2.2.0'
implementation 'org.springframework.boot:spring-boot-starter-aop'   // 어노테이션 AOP (현재 없음, 추가 필요)
// actuator: 이미 있음. micrometer-registry-prometheus: 이미 있음 → 서킷 상태 메트릭 자동 노출
```

`spring-retry` 가 이미 있지만, Resilience4j 로 일원화한다(서킷과 같은 라이브러리에서 재시도·상태를 묶어 관리/메트릭). spring-retry 는 당장 제거하지 않되 신규 사용 안 함.

---

## 3. 인스턴스별 정책 (초기값 — 운영 시 조정, ADR §11)

> ⚠️ 아래 숫자는 **합리적 기본값**이며 강사님 컨펌·실측 후 조정 대상. application.yml 한 곳에 모아 숫자만 바꾸게 한다.

| 인스턴스 | 대상 | 타임아웃 | 재시도 | 서킷(실패율/윈도우) | 근거 |
|---------|------|---------|--------|--------------------|------|
| `llm` | Grok/Claude/Gemini | 30s | 0 (LLM 재호출 비쌈·비결정) | 50% / 10 | 응답 본래 느림. 재시도 X (사용자 대기·비용) |
| `embed` | FastAPI 임베딩 | 5s | 1 | 50% / 10 | 빨라야 정상. 일시 실패 1회 재시도 |
| `vector` | Pinecone 검색/upsert | 5s | 1 | 50% / 10 | 동상 |
| `imagegen` | Bria 생성 | 60s | 0 | 50% / 5 | 매우 느림+폴링. 재시도 X(중복 생성·과금) |

공통 서킷 기본: `waitDurationInOpenState: 30s`, `minimumNumberOfCalls: 5`, `permittedNumberOfCallsInHalfOpenState: 3`, `automaticTransitionFromOpenToHalfOpenEnabled: true`.

재시도 대상 예외: `TimeoutException`, `IOException`, 5xx. **4xx·BusinessException(CustomException)은 재시도 안 함**(클라이언트 잘못이라 재시도 무의미).

---

## 4. 폴백 정책 (구현 후 확정)

**메서드 레벨 `fallbackMethod` 을 두지 않는다.** 코드 확인 결과 상위 경로가 이미 graceful 처리하기 때문:

- `embed`/`vector` 실패(서킷 open 의 `CallNotPermittedException` 포함) → 예외를 **그대로 전파**. 검색 경로는 `ChatLlmService.handleSearchDecision` 의 `try-catch(Exception)` 가 받아 `SEARCH_BLOCKED` 발화 + 빈 레퍼런스 반환(`return List.of()`) → offerGenerate 경로. 적재 경로(`embedImage`/`upsert`)는 `AiImageIndexService` 의 비동기 `catch` 가 로그만 남김. **별도 폴백 불필요.**
- `llm`·`imagegen`(Bria) → **서킷 자체를 안 붙인다**(아래). 실패는 기존대로 `CustomException(AI_SERVICE_ERROR)` 로 변환되어 상위가 처리.

### 4.1 LLM·Bria 는 서킷 제외, 타임아웃만 (구현 결정)

LLM 3종·Bria 는 이미 모든 실패를 `CustomException(AI_SERVICE_ERROR)` 로 변환하고(서킷의 `ignore-exceptions` 대상), Bria 는 자체 폴링(최대 30s)·에러 처리가 견고하다. '생성 실패'라는 정상적 비즈니스 결과까지 서킷을 여는 것은 과민하다. → **이들에는 `@CircuitBreaker` 를 붙이지 않고 connect/read 타임아웃만** 적용한다(`HttpClientFactory`, connect 3s / read 30s). 서킷·재시도 인스턴스는 `embed`/`vector` 만 정의한다.

---

## 5. 적용 지점

| 클라이언트 | HTTP | 타임아웃 적용 | 어노테이션 |
|-----------|------|--------------|-----------|
| `FastApiClient.embedText/embedImage` | WebClient | `.timeout(5s)` (`fastapi.timeout-ms`) | `@CircuitBreaker(name="embed")` `@Retry(name="embed")` |
| `PineconeClient.queryByVector/upsert` | WebClient | `.timeout(5s)` (`pinecone.timeout-ms`) | `@CircuitBreaker(name="vector")` `@Retry(name="vector")` |
| `BriaClient.generate` | RestClient | `HttpClientFactory` connect 3s/read 10s | (없음 — 서킷 제외) |
| `GrokService`/`ClaudeService`/`GeminiService` | RestClient | `HttpClientFactory` connect 3s/read 30s | (없음 — 서킷 제외) |

> `HttpClientFactory`(global/client, 신규) = connect/read 타임아웃 박힌 RestClient 공통 팩토리. RestClient 4종이 공유.
> 폴백 메서드는 두지 않음(§4). embed/vector 예외는 상위가 graceful 처리.

**확인 완료** — HTTP 클라이언트 분포:
- RestClient(동기): BriaClient, GrokService, ClaudeService, GeminiService (4개). 전부 `RestClient.create()` → 타임아웃은 `ClientHttpRequestFactory`(connect/read)로 통일 적용.
- WebClient+`.block()`: FastApiClient, PineconeClient (2개). reactor-netty 1.1.17 존재 → `HttpClient.responseTimeout()` 또는 호출에 `.timeout(Duration)`.

→ RestClient 4개는 **공통 팩토리 빌더 헬퍼**로 connect/read 타임아웃을 주입(중복 제거). 인스턴스별 타임아웃 값만 다르게.

---

## 6. 산출물 (예정)

- `build.gradle` — resilience4j-spring-boot3 + starter-aop
- `application.yml` — resilience4j.* (인스턴스별 §3)
- 6개 클라이언트/서비스 — 타임아웃 + 어노테이션 + 폴백 메서드
- (선택) 서킷 상태 로깅용 이벤트 리스너

---

## 7. 검증

- 단위: 폴백 메서드가 예외 시 의도한 값 반환하는지 (mockito 로 클라이언트 예외 주입)
- 수동: FastAPI/Pinecone 끄고 채팅 → 앱이 hang 안 하고 "검색 없음" graceful 인지
- actuator: `/actuator/health` 에 서킷 상태, `/actuator/prometheus` 에 `resilience4j_circuitbreaker_*` 메트릭 노출 확인
