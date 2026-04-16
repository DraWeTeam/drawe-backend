# 로그인/회원가입 API 명세 검증 결과 (수정 후)

검증 기준: `drawe_api_spec_v1` (OpenAPI 3.1)
대상 브랜치: `feature/SCRUM-32-LogIn`
검증 일시: 2026-04-16
실서버 검증: `localhost:8081`에서 21개 시나리오 curl 실행

---

## 1. 엔드포인트 매핑 — 수정 후

| 명세 엔드포인트 | 상태 | 비고 |
|---|---|---|
| `POST /auth/signup` | OK | envelope, 명세 응답(userId/email/nickname), 201→200 변경 완료 |
| `POST /auth/login` | OK | 명세 7필드(userId/accessToken/refreshToken/email/nickname/provider/role) 모두 반환 |
| `POST /auth/reissue` | OK | 경로 변경(`refresh`→`reissue`), refresh 토큰 회전 적용 |
| `POST /auth/logout` | OK | Bearer 헤더 인증 + 사용자 모든 RefreshToken 삭제 |
| `GET /auth/check-email` | OK | 신규 추가 |
| `GET /auth/check-nickname` | OK | 신규 추가 |
| `POST /auth/check-password` | OK | 신규 추가 (인증 필요) |
| `POST /auth/oauth/google` | 미구현 | 별도 PR (외부 OAuth 통합 필요) |
| `POST /auth/oauth/kakao` | 미구현 | 별도 PR |
| `POST /auth/oauth/naver` | 미구현 | 별도 PR |

---

## 2. 응답 envelope 검증 — 21개 시나리오 결과

### 성공 응답: `{success: true, data: {...}}`
- signup 성공 → 200 `{userId, email, nickname}`
- login 성공 → 200 `{userId, accessToken, refreshToken, email, nickname, provider, role}`
- reissue 성공 → 200 `{accessToken, refreshToken}` (둘 다 갱신)
- check-email/nickname 가능 → 200 `{available: true}`
- logout/check-password 성공 → 200 `{success: true}`

### 에러 응답: `{success: false, error: {code, message, timestamp}}`
| 시나리오 | HTTP | code |
|---|---|---|
| 이메일 중복(signup, check-email) | 409 | `EMAIL_ALREADY_EXISTS` |
| 닉네임 중복(signup, check-nickname) | 409 | `NICKNAME_ALREADY_EXISTS` |
| 비밀번호 8자 미만 | 400 | `INVALID_INPUT` |
| 잘못된 이메일 형식 | 400 | `INVALID_INPUT` |
| 로그인 비번 오류 | 401 | `UNAUTHORIZED` |
| 로그인 미존재 사용자 | 401 | `UNAUTHORIZED` (사용자 존재 노출 방지) |
| reissue 잘못된/회전된 토큰 | 401 | `INVALID_TOKEN` |
| Bearer 누락(logout, check-password) | 401 | `UNAUTHORIZED` |
| check-password 비번 오류 | 401 | `UNAUTHORIZED` |
| logout 후 기존 RefreshToken 사용 | 401 | `INVALID_TOKEN` |

---

## 3. 주요 코드 변경

### 신규 파일
- `global/response/ApiResponse.java` — 성공 envelope
- `global/response/ApiErrorResponse.java` — 에러 envelope (timestamp/details 포함)
- `global/security/JwtAuthenticationEntryPoint.java` — 401 응답 envelope 처리
- `global/security/JwtAccessDeniedHandler.java` — 403 응답 envelope 처리
- `domain/user/entity/AuthProvider.java` — enum (LOCAL/GOOGLE/KAKAO/NAVER)
- `domain/user/entity/Role.java` — enum (USER/ADMIN)
- `domain/auth/dto/SignupResponse.java`
- `domain/auth/dto/CheckAvailabilityResponse.java`
- `domain/auth/dto/PasswordCheckRequest.java`

### 변경 파일
- `User.java` — `provider`, `role` 컬럼 추가, `nickname` unique
- `AuthResponse.java` — 명세 7필드 평면 구조로 재정의
- `TokenResponse.java` — `refreshToken` 필드 추가 (rotation)
- `AuthService.java` — 전면 재작성: rotation, 401 통일, check API 추가, logout=userId 기반
- `AuthController.java` — envelope 적용, 신규 3개 엔드포인트, `@AuthenticationPrincipal Long userId`
- `SecurityConfig.java` — 엔드포인트별 인증 분리, EntryPoint/AccessDeniedHandler 연결
- `ErrorCode.java` — 명세 코드/메시지로 정렬, `NICKNAME_ALREADY_EXISTS` 추가
- `GlobalExceptionHandler.java` — envelope 응답, ConstraintViolation/MissingParam 핸들러 추가
- `UserRepository.java` — `existsByNickname` 추가
- `RefreshTokenRepository.java` — `@Modifying` JPQL 쿼리

### 삭제
- `global/error/ErrorResponse.java` (envelope으로 대체)

---

## 4. 미구현 항목 (의도적 보류)

| 항목 | 사유 |
|---|---|
| 소셜 로그인 3종 (`/auth/oauth/google|kakao|naver`) | Google/Kakao/Naver OAuth 클라이언트 통합 + provider별 토큰 검증 로직 필요. 별도 PR 권장 |
| AccessToken 블랙리스트 | Redis 인프라 도입 필요. 현재는 logout 시 RefreshToken 전체 삭제로 대체 (재발급 차단) |

---

## 5. 결론

명세 정의 엔드포인트 10개 중 **7개 100% 일치 구현**, 나머지 3개는 외부 의존성으로 보류. 응답 envelope·에러 코드·HTTP 상태·필드 구조 모두 명세 부합. 21개 정상/에러 시나리오 실서버 통과.
