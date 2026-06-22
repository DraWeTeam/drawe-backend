# S3 가이드 이미지 — Pinecone namespace 분리 설계

> 작성일: 2026-06-12
> 짝 문서:
> - [`frontend-handoff-ai-image.md`](./frontend-handoff-ai-image.md) — AI 이미지 생성·노출 계약
> - [`AI-pipeline-review-decisions.md`](./AI-pipeline-review-decisions.md) — 상위 ADR
>
> 이 문서의 성격: **선행 확장점 설계**. 가이드용 FastAPI(Gemini 생성)가 아직 미존재인 시점에,
> 그 적재 경로가 붙을 자리를 추측 없이 미리 비워두기 위한 결정 기록.

---

## 0. 배경 (TL;DR)

DraWe 의 AI 이미지는 두 갈래로 나뉜다.

| 갈래 | 생성 | 용도 | 현재 |
|------|------|------|------|
| **일반** | Bria (외부 직접) | 사용자 레퍼런스 자료 | ✅ 운영 중 |
| **가이드** | FastAPI 내부에서 Gemini | 이론적 가이드 설명용 구도·뼈대 이미지 | ⬜ 미존재 (FastAPI 신설 예정) |

두 갈래 모두 **같은 적재 파이프라인**을 탄다:
`AiImageCreatedEvent` → `AiImageIndexService` → CLIP 임베딩(`FastApiClient.embedImage`) → `PineconeClient.upsert`.

경계 결정(별도 합의 완료): **생성은 FastAPI, 적재는 Spring Boot.**
FastAPI 는 그림 분석→계획→Gemini 생성까지만 하고 이미지 바이트(또는 임시 URL)를 Spring Boot 로 반환한다.
Spring Boot 는 받은 바이트를 `ImageStorage` 에 저장 + `Image` 엔티티 생성 + `AiImageCreatedEvent` 발행으로
**기존 Bria 적재 로직을 그대로 재사용**한다. (DB·Pinecone 자격증명·트랜잭션 경계를 한 서버에 모으기 위함.)

---

## 1. 문제 — 같은 인덱스에 섞이면 일반 검색이 오염된다

가이드 이미지는 "이론 설명용 구도 예시"라, **사용자의 일반 레퍼런스 검색 결과에 섞여 나오면 안 된다.**
그런데 현재 적재·검색 구조는:

- `PineconeClient.queryByVector(vector, topK)` — **메타데이터 필터가 없다.** vector + topK 만 보낸다.
- `image_source` 메타 키는 적재 시 **쓰기만** 하고, 검색에서 **읽지 않는다** (Java 코드에 필터 경로 없음).

→ 가이드 벡터를 같은 namespace(현재는 default `""`)에 그냥 적재하면, 일반 검색이 **구조적으로 가이드를 걸러낼 방법이 없다.**

---

## 2. 결정 — 별도 namespace 로 물리 분리

가이드 벡터는 **별도 Pinecone namespace**(예: `"guide"`)로 적재한다. 일반(Bria/Unsplash) 벡터는
default namespace(`""`)를 그대로 유지한다.

**근거 (Pinecone 공식 동작):**
- `namespace` 는 upsert/query **요청 본문(body) 필드**. 미지정 시 default `""`.
- query 는 **한 번에 하나의 namespace 만** 검색한다. → 일반 검색(default)은 가이드를 *절대* 잡지 않고,
  가이드 검색은 그 namespace 를 *명시할 때만* 잡는다. cross-contamination 이 **구조적으로 불가능**하다.

**왜 "같은 인덱스 + image_source 필터" 가 아니라 namespace 인가:**
- 필터 방식은 검색 핫패스(`queryByVector`)에 필터 인자를 추가하고 *모든* 일반 검색에 `image_source != GUIDE`
  조건을 거는 식이라, 필터를 한 번이라도 빠뜨리면 가이드가 샌다 (실수에 취약).
- namespace 분리는 일반 검색 코드를 **전혀 건드리지 않아도** 격리가 성립한다 (default 유지 = 기존 동작 보존).

---

## 3. 이번에 한 것 — 확장점만 (코드)

가이드 FastAPI 스키마가 미확정이므로 **추측성 구현은 하지 않는다.** 적재 경로가 붙을 자리만 비워둔다.

| 변경 | 파일 | 내용 | 기존 동작 |
|------|------|------|----------|
| upsert namespace 배선 | `PineconeUpsertRequest` | nullable `namespace` 필드 추가, `@JsonInclude(NON_NULL)`. 기존 1-인자 생성자는 `namespace=null` 위임 | 불변 (null → default `""`) |
| upsert 오버로드 | `PineconeClient.upsert` | namespace 받는 4-인자 오버로드 추가, 기존 3-인자는 위임. `@CircuitBreaker`/`@Retry` 는 실제 HTTP 치는 4-인자에 위치 | 불변 |
| 출처 파라미터화 | `AiImageIndexService.buildMetadata` | `image_source="AI_GENERATED"` 하드코딩 → `SOURCE_AI_GENERATED` 상수 + `imageSource` 주입 오버로드 | 불변 (기본값 = AI_GENERATED) |

검증: 컴파일 + 기존 테스트 통과 (호출부는 `PineconeClient`·`AiImageIndexService` 각 1곳, 테스트 직접 참조 없음).

---

## 4. 아직 안 한 것 — TBD (B 개발자님 / 기획 합의 포인트)

가이드 FastAPI 가 나오고 그 응답 스키마가 확정되어야 결정 가능하다. 지금 박으면 추측이 된다.

1. **가이드 namespace 이름** — `"guide"` 잠정. 운영 컨벤션 확정 필요.
2. **가이드 `image_source` 출처값** — 예: `"AI_GUIDE"`. 같은 namespace 안에서도 출처 식별용.
3. **`ImageSource` enum + V7 마이그레이션** — 현재 `('UNSPLASH','AI')`. 가이드를 DB에서도 구분하려면
   `AI_GUIDE` 추가 여부 결정. (MySQL `images.source` enum)
4. **가이드 검색 경로** — `queryByVector` 에 namespace 인자를 받는 오버로드 추가 시점. 가이드를 *검색*해서
   쓰는 기능(이론 설명에 구도 예시 첨부 등)이 설계될 때.
5. **가이드 metadata 스키마** — 설계 메모의 `value_structure / color_harmony / light_direction /
   composition_balance` 등 enum 강제 필드. **가이드 도메인 지식**이라 기획·B님과 합의 후 확정.
6. **가이드 적재 트리거** — FastAPI→Spring Boot 응답을 받는 클라이언트 신설 + `AiImageCreatedEvent`
   변형(또는 출처 필드 추가) 배선.

---

## 5. 한 줄 요약

> 가이드 벡터는 **별도 namespace** 로 분리한다(일반 검색 무오염을 구조적으로 보장). 이번엔 적재 경로의
> **확장점(upsert namespace + 출처 파라미터)만** 비워뒀고, 검색 경로·enum·가이드 메타 스키마는 가이드
> FastAPI 확정 후 합의해서 채운다.
