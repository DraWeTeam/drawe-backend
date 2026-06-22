# 인계 문서 — P0-1 이미지 서명 URL 작업 (2026-06-08)

## 터미널 복구 먼저
- 터미널 밀림/스크롤 깨짐은 보통 렌더링 문제. 순서대로:
  1. `Ctrl+L` (화면 다시 그리기)
  2. PowerShell 창 리사이즈/최대화 (강제 redraw)
  3. 그래도 안 되면 Claude Code 종료 후 **`claude --continue`** (또는 `claude -c`) 로 이 대화 복구.
- 작업물(코드/메모리)은 디스크에 저장됨. 세션 끊겨도 안전.

## 지금까지 한 일 (요약)
원래 요청: `docs/decisions/AI-pipeline-review-decisions.md` 기준 트랙 **A**, S0 점검 → S1 진입 가능 여부 확인.

### 1. S0 점검 결과 = 사실상 완료
- `src/main/java/com/drawe/backend/domain/llm/contract/` 에 계약 7파일 완성 (IntentCode, IntentResult, IntentRouting, ReferenceImage, StepContext, StepExecutor, StepType). 정의만 존재(코드 동결 OK), 전체 컴파일 통과.
- 009 → IntentResult.referencedImages 앵커 슬롯 분리, 정적 전략 맵(IntentRouting.ROUTING) 채택. ADR §3/§5 와 일치.

### 2. 우선순위 재정렬 (중요)
- ADR S1'(intent 룰/분류기)는 "베타 후" 진입인데, 베타(2026.05.22~28, 4명) 이미 종료.
- 베타 결과 1순위는 룰/분류기가 **아니라** 출시 전 필수 **P0-1 이미지 표시 버그**. → 이걸 먼저 처리하기로 결정.
- 이후 베타 고도화 순위: ①레퍼런스 추천 관련성+포즈/인체 매칭(만족도 2.5/5) ②인터랙션 발견성 ③가이드 인지·품질.

### 3. P0-1 진단 (확정)
- AI 이미지가 깨지는 근원: `/images/{id}` 가 **JWT Bearer 헤더 인증 필수 + 소유자 검증**. 브라우저 `<img src>` 엔 헤더가 안 실려 401/403 → AI 이미지만 깨짐(Unsplash 는 외부 URL 이라 무관).
- `JwtAuthenticationFilter` 는 헤더 토큰만 읽음(쿠키 폴백 없음). 추천 보드는 타인 AI 이미지도 노출 → 소유자 검증으로도 403.

### 4. 구현 = 서명된 URL (signed URL) — 완료, 컴파일 통과
노출 직전 `/images/{id}?exp=<epoch>&sig=<HMAC-SHA256>` 발급. 시크릿 `jwt.secret` 재사용, TTL `image.url.ttl-seconds` 기본 3600s. **저장은 상대경로 유지, 응답 순간에만 서명**(만료 URL DB 저장 금지).

변경 파일:
- `domain/image/service/ImageUrlSigner.java` (신규) — sign()/verify(), 상수시간 비교, 비대상 URL 통과
- `global/config/SecurityConfig.java` — `GET /images/*` permitAll (POST 업로드는 인증 유지)
- `domain/image/controller/ImageController.java` — 소유자 검증 → 서명 검증
- `domain/llm/service/ChatLlmService.java` — 4개 노출 지점 서명(GeneratedImage, GenerateImageResponse, signReferenceUrls, 히스토리). 저장용 refs 는 상대경로 유지(함정 회피)
- `domain/llm/dto/ChatHistoryResponse.java` — from(LlmMessage, ImageUrlSigner)
- `domain/project/service/PinService.java`, `domain/onboarding/service/OnboardingService.java` — URL 서명

## 다음에 할 것
1. **동작 확인** (사용자가 한번에 파악하겠다고 함): 앱 띄워서 AI 이미지 생성 → 채팅/보드/핀/히스토리에서 이미지 정상 표시되는지. `/verify` 또는 `/run` 활용 가능.
2. 확인되면 **커밋** (아직 커밋 안 함). 현재 브랜치 `feature/SCRUM-60-AI-IMAGE`.
3. 미해결 확인거리: TTL 1시간이 긴 세션에 충분한지(프론트 재조회 시 새 서명 받으므로 보통 OK).

## 빌드 주의
실행 중인 앱이 `build/` 디렉터리를 잠금. 컴파일 검증은:
`.\gradlew.bat compileJava "-Dorg.gradle.project.buildDir=build_check"` 후 `build_check` 삭제.

## 관련 메모리
- `ai-pipeline-s0-status.md`, `p0-image-signed-url.md` (memory 디렉터리에 저장됨)
