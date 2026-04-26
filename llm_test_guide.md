# LLM 그림 가이드 테스트 진행 가이드 (Phase 1)

`drawe_api_spec_v1.pdf` 의 Project Chat 스펙(`POST /projects/{projectId}/chat` 등)에 맞춰 구현한 Phase 1 버전.
LLM 응답 품질 비교는 **웹 콘솔(Google AI Studio / xAI Console)** 에서 직접 진행하고, 백엔드는 API 흐름 검증에 집중한다.

---

## 0. 범위

- 스펙 호환 경로 / 요청·응답 구조 구현
- 페르소나(SYSTEM 메시지)는 서버 내부에서 자동 적용
- 이미지 입력은 `imageUrl` 필드에 **base64 data URL** 형식으로 받음 (Cloudinary 도입 시 진짜 URL로 자연 교체)
- `references=[]`, `type="guide"` 고정 — 실제 분기는 Pinecone/CLIP 도입 후 Phase 3에서

---

## 1. 사전 준비

### 1-1. API 키
- Gemini: https://aistudio.google.com → API key
- Grok: https://console.x.ai → API key
- `src/main/resources/application-llm.properties` 에 입력 (이 파일은 `.gitignore`)
- 프로바이더 선택은 `llm.default-provider=gemini` 또는 `grok` 으로 설정

### 1-2. 테스트용 데이터
DB에 직접 INSERT 또는 회원가입 흐름으로 준비.

| 항목 | 비고 |
|---|---|
| User | 일반 회원가입 또는 구글 OAuth로 1개 생성 |
| Project | 해당 user 소유로 1개 생성 (`status=IN_PROGRESS`) |
| 테스트 이미지 | 로컬에 1~2장 준비, base64 인코딩 후 사용 |

JWT는 `/auth/login` 응답으로 받아서 모든 요청 헤더에 사용.

### 1-3. base64 변환 방법
```bash
# 터미널에서 한 번에 data URL 생성
echo "data:image/jpeg;base64,$(base64 -w 0 my_drawing.jpg)" > image.txt
```

### 1-4. 빌드 / 실행
```bash
./gradlew clean build
./gradlew bootRun
```
빌드 시 `Unable to delete directory` 가 나오면:
```bash
taskkill //F //IM java.exe
./gradlew --stop
```

---

## 2. 페르소나 — "01 친근한"

세션 첫 호출 시 SYSTEM 메시지로 자동 등록 (`PersonaRegistry.FRIENDLY_01`).

### 정체성
- 역할: 친구
- 전문성 수준: 중간 — 아는 건 편하게 알려주고, 어려운 부분은 레퍼런스와 함께 "이렇게 해보면 어떨까?" 정도 조언
- 의인화 수준: 높음 — 감탄사·말줄임을 자연스럽게 사용

### 관계성
- 주도성: 사용자 주도 ●●○○○ AI 주도
- 거리감: 가까움 ●○○○○ 멀음
- 개입 수준: 최소 ●●○○○ 적극
- 감정 개입: 중립 ●●●●○ 공감적

### 화법 규칙
- **문장 길이**: 1~2문장 위주, 긴 설명은 짧게 끊어 이어감
- **존댓말**: "~해요", "~인 것 같아요"
- **금지 표현**: "잘 하셨어요"(맥락 없는 칭찬) / "보통은 이렇게 해요" / "다른 분들은~" / "틀렸어요"
- **허용 예시**: "오, 이 구도 재미있는데요!" / "같이 찾아볼까요?" / "어떤 느낌이에요?"
- **이모지**: 맥락 있을 때 1개 허용

### 자율성 경계
- 자율 행동: 레퍼런스 분위기 추천 · 칩 자동 제안 · 가벼운 공감
- 확인 필요: 그림 방향 전환 제안 · 새 프로젝트 시작 유도
- 금지 행동: 평가 · 비교 · 수정 강요 · 풀 가이드 선제 제공

### 감정 규범
| 사용자 감정 | 드로 반응 |
|---|---|
| 막막함 | "이런 느낌으로 해보는 건 어떨까요?" |
| 성취감 | "완성했군요! 어때요?" |
| 좌절 | "잠깐 쉬어도 괜찮아요" |

---

## 3. API 엔드포인트

모든 요청 헤더: `Authorization: Bearer <JWT>`

### 3-1. 채팅 (메시지 전송)
```
POST /projects/{projectId}/chat
Content-Type: application/json

{
  "message": "이 부분 명암을 어떻게 잡으면 좋을까요?",
  "sessionId": null,
  "imageUrl": "data:image/jpeg;base64,/9j/4AAQ..."
}
```
- `sessionId` 가 null/빈 문자열이면 **새 세션 생성** + 페르소나 SYSTEM 메시지 자동 등록
- `imageUrl` 은 `data:image/...;base64,...` 형식만 지원 (Phase 1)

응답 (ApiResponse 래퍼 안에 들어감):
```json
{
  "success": true,
  "data": {
    "sessionId": "...",
    "type": "guide",
    "message": "오, 빛이 왼쪽에서 들어오는 느낌이네요! ...",
    "references": [],
    "followUp": null
  }
}
```

### 3-2. 히스토리 조회
```
GET /projects/{projectId}/chat/{sessionId}/history
```
응답
```json
{
  "success": true,
  "data": {
    "sessionId": "...",
    "messages": [
      { "role": "user", "content": "...", "createdAt": "..." },
      { "role": "assistant", "content": "...", "createdAt": "..." }
    ]
  }
}
```
SYSTEM 메시지는 응답에서 제외(스펙 동일).

### 3-3. 세션 리셋
```
POST /projects/{projectId}/chat/{sessionId}/reset
```
- SYSTEM 제외한 모든 메시지 삭제 (페르소나는 유지)
- 응답: `{ "success": true, "data": { "success": true } }`

---

## 4. 테스트 시나리오

### A — 새 세션 생성 + 페르소나 적용
1. `POST /projects/1/chat` 에 `sessionId=null`, `message="수채화 처음인데 어디부터 시작할까요?"`
2. 응답 `sessionId` 값 저장
3. 응답 `message` 가 페르소나 화법 규칙 준수 여부 확인 (짧은 존댓말 / 금지 표현 미출현)

### B — 이미지 첨부 호출
1. 같은 `sessionId` 사용
2. `imageUrl` 에 base64 data URL 첨부
3. 응답이 이미지 내용을 반영해 답하는지 확인

### C — 히스토리 누적
1. 동일 `sessionId` 로 2~3턴 진행
2. 다음 턴에서 "아까 첫 번째에 말한 것 다시 알려줘요" 같은 참조 질문
3. 응답이 이전 맥락 반영하는지 확인
4. `GET /history` 호출로 USER/ASSISTANT 시간순 누적 확인

### D — 리셋 후 컨텍스트 단절
1. `POST /reset` 호출
2. 같은 세션에서 이전과 같은 참조 질문 → 모르겠다는 반응 기대 (히스토리 초기화 검증)
3. `GET /history` 가 빈 배열 반환 확인

### E — 인증 / 권한
1. JWT 없이 호출 → **401**
2. 다른 user의 projectId/sessionId 접근 → **403**
3. 없는 projectId → **404**

### F — 에러 케이스
1. API 키 비워둔 채 호출 → **503 AI_SERVICE_ERROR**
2. `imageUrl` 에 잘못된 형식(예: `http://` URL) → **400 INVALID_INPUT** (Phase 2에서 지원)

---

## 5. 검증 체크리스트

### 기능
- [ ] 첫 호출 시 SYSTEM 메시지가 DB에 저장됨
- [ ] 호출마다 SYSTEM + 과거 메시지가 LLM 요청에 포함됨
- [ ] base64 data URL이 정상 디코딩되어 LLM에 전달됨
- [ ] Grok / Gemini 양쪽 모두 같은 `ChatResponse` DTO로 매핑됨
- [ ] `LlmMessage` 에 `latency_ms / model / status` 기록됨
- [ ] 실패 호출은 `status=FAILED` 로 남고 다음 호출 시 history에서 제외됨

### 페르소나 (웹 콘솔에서 동일 프롬프트로 비교)
- [ ] 응답 문장 길이 1~2문장 위주
- [ ] 존댓말 일관 ("~해요", "~인 것 같아요")
- [ ] 금지 표현 미출현
- [ ] 이모지 0~1개

### 인증 / 보안
- [ ] JWT 없이 401
- [ ] 다른 user 리소스 403
- [ ] API 키가 응답·로그에 노출 안 됨

---

## 6. 모델 비교 (웹 콘솔)

백엔드 통합 테스트가 아닌 **웹에서 직접** 같은 프롬프트 + 이미지로 응답 품질 비교.

| 항목 | Gemini | Grok |
|---|---|---|
| 페르소나 준수도 (1~5) | | |
| 이미지 인지 정확도 (1~5) | | |
| 한국어 자연스러움 (1~5) | | |
| 평균 응답 시간 | | |
| 비용 | | |
| 종합 평가 | | |

→ 채택된 모델로 `llm.default-provider` 변경.

---

## 7. 다음 단계

- **Phase 2**: Cloudinary 연결. 별도 `POST /upload/image` 엔드포인트로 진짜 URL 받고, `chat` 의 `imageUrl` 에 그대로 전달 (서버는 base64 분기 외에 http URL 다운로드 분기 추가).
- **Phase 3**: CLIP 임베딩 + Pinecone 검색. `references` / `type` / `followUp` 필드 채움.
- 본 문서 시나리오는 Phase 2~3 회귀 테스트로 재사용.
