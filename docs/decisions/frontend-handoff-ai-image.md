# 프론트엔드 작업 인계 — AI 이미지 생성 플로우

> 작성일: 2026-05-21
> 대상: 프론트엔드 담당 Claude
> 백엔드 변경 PR/브랜치: `feature/SCRUM-60-AI-IMAGE`

## 배경

백엔드에서 "사용자가 채팅으로 'AI 이미지 만들어줘'라고 명시 요청하면 검색·LLM 답변을 건너뛰고 **즉시 Bria로 이미지 생성**하는 분기"를 추가했다. 기존 "AI 이미지 생성 버튼" 플로우는 그대로 유지된다 (검색 결과가 부적합할 때 fallback용).

프론트에 처리해야 할 작업이 3가지 있다.

---

## 작업 1 — `ChatResponse.generatedImage` 렌더링 (필수)

### 현상
사용자가 "강아지 그림 만들어줘"라고 보내면, 백엔드는 즉시 생성된 이미지 URL을 응답에 담아 돌려준다.
**현재 프론트는 이 필드를 읽지 않아 채팅 텍스트만 보이고 이미지는 안 보임.**
새로고침하면(=히스토리 GET 재호출) 이미지가 보이는 이유는, 히스토리는 별도 필드(`imageUrl`)로 이미지가 따라오기 때문.

### 백엔드 응답 스키마 (변경됨)

`POST /api/projects/{projectId}/chat` 응답 body:

```ts
type ChatResponse = {
  sessionId: string;
  type: "guide";
  message: string;                       // 어시스턴트 텍스트
  references: ReferenceItem[];           // 검색 레퍼런스 (없으면 [])
  referencesAction: "NEW_SEARCH" | "KEEP" | "SKIP" | "GENERATE_NOW";  // ← 새 값 추가됨
  offerGenerate: boolean;                // 기존 "생성 버튼 노출" 신호
  suggestedPrompt: string | null;
  generatedImage: {                      // ← 새 필드, GENERATE_NOW 분기에서만 채워짐
    imageId: number;
    url: string;                         // 예: "/images/13"
    prompt: string;                      // 실제 생성에 쓰인 영문 프롬프트
  } | null;
};
```

### 처리 요구사항

채팅 응답을 받았을 때:

```ts
if (response.generatedImage) {
  // 어시스턴트 메시지에 이미지를 첨부해서 렌더
  renderAssistantMessage({
    text: response.message,             // "요청하신 이미지를 만들어드렸어요..."
    imageUrl: response.generatedImage.url,
    badge: "AI",                        // 기존 AI 배지 컴포넌트 재사용
  });
} else if (response.offerGenerate) {
  // 기존 동작: "AI 이미지 생성" 버튼 노출
  renderAssistantMessage({
    text: response.message,
    actionButton: {
      label: "AI 이미지 생성",
      onClick: () => callGenerateImage(response.suggestedPrompt),
    },
  });
} else {
  // 일반 답변 (references 인용 등)
  renderAssistantMessage({ text: response.message, references: response.references });
}
```

### 이미지 URL 주의
- `/images/13` 같은 상대 경로로 옴. 백엔드 base URL을 앞에 붙여서 절대 URL로 만들어야 함 (예: `http://localhost:8081/images/13`).
- 히스토리 GET에서는 `LlmMessage.imageUrl` 필드로 같은 형식의 URL이 옴 — 같은 prefix 처리 로직 재사용 가능.

---

## 작업 2 — "이미지 만들고 있어요" 로딩 표시 (UX 개선)

### 현상
백엔드가 Bria API를 폴링하므로 **이미지 생성에 15~25초 정도 걸린다.**
그 동안 프론트가 응답만 기다리고 있어 사용자는 "보냈는데 왜 반응이 없지?" 라고 느낀다.
반면 기존 "AI 이미지 생성 버튼"을 눌렀을 때는 로딩 UI가 있어서 통일감이 없다는 피드백.

### 처리 요구사항

옵션 A — **클라이언트 사이드 임시 메시지 (권장, 간단)**

채팅 입력을 보낸 직후, 메시지 내용이 다음 패턴을 포함하면 임시 어시스턴트 버블을 띄운다:
- 한국어 패턴: `만들어`, `그려`, `생성`, `AI`, `이미지`
- 정확하지 않아도 됨. 응답이 오면 임시 버블을 진짜 응답으로 교체.

```ts
// 메시지 전송 시
const isLikelyGenerate = /만들|그려|생성|AI|이미지/i.test(userMessage);
sendMessage(userMessage);
if (isLikelyGenerate) {
  showTemporaryAssistantBubble({
    text: "이미지 만들고 있어요... (보통 15~25초 정도 걸려요)",
    spinner: true,
  });
}

// 응답 수신 시
removeTemporaryAssistantBubble();
renderAssistantMessage(response);  // 작업 1의 로직
```

옵션 B — 백엔드 SSE/WebSocket으로 진행 이벤트 푸시
- 작업 큼. 지금은 옵션 A로 충분.

기존 "AI 이미지 생성 버튼" 클릭 시 사용하던 로딩 컴포넌트를 재사용하면 통일감 확보.

---

## 작업 3 — `referencesAction === "GENERATE_NOW"` 식별 (선택)

`generatedImage` 필드만 보면 1번 작업은 끝나지만, 분석/디버깅용으로 `referencesAction` 값이 `"GENERATE_NOW"`인 메시지를 식별해두면 좋다.
예: 디버그 로그, 사용량 통계, 또는 "AI 생성" 배지 강조 등.
필수는 아님.

---

## 테스트 시나리오

작업 1·2 완료 후 아래 시나리오로 확인:

| 입력 | 기대 동작 |
|---|---|
| "강아지 그림 만들어줘" | 임시 "만들고 있어요" 버블 → 15~25초 후 → 생성된 이미지 + 텍스트로 교체 |
| "비슷한 걸로 만들어줘" (이전 컨텍스트 있을 때) | 위와 동일 |
| "강아지 레퍼런스 보여줘" | 검색 결과 references 그리드로 표시 (이미지 생성 아님) |
| "벚꽃 그리는 법 알려줘" | 검색 + 텍스트 답변, references 인용 |
| 검색 결과 없는 모호한 키워드 | `offerGenerate: true` → "AI 이미지 생성" 버튼 (기존 동작) |
| 생성 직후 새로고침 | 히스토리에서 이미지가 그대로 보임 (이미 정상) |

---

## 변경 안 된 것 (참고)

- **`POST /api/projects/{projectId}/chat/generate` (기존 생성 API)** — 그대로 살아있음. "AI 이미지 생성" 버튼 클릭 시 호출.
- **`GET /api/projects/{projectId}/chat/{sessionId}/history`** — 응답 스키마 변경 없음. 메시지의 `imageUrl` 필드는 그대로.
- **`references`, `offerGenerate`, `suggestedPrompt`** 등 기존 필드 — 의미·동작 동일.

---

## 백엔드 디버깅 키워드 (필요 시)

서버 로그에 다음 라인이 찍히면 GENERATE_NOW 분기가 탔다는 의미:
```
KeywordExtractor : 즉시 생성 요청: '<원문>' → '<영문 프롬프트>'
ChatLlmService   : GENERATE_NOW 처리 완료: user=..., imageId=..., prompt='...'
```

레퍼런스 검색 분기는 기존대로:
```
KeywordExtractor : 새 검색: '<원문>' → '<영문 키워드>'
ChatLlmService   : ========== 검색 분석 ==========
```
