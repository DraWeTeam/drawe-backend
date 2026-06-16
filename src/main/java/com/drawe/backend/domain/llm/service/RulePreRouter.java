package com.drawe.backend.domain.llm.service;

import com.drawe.backend.domain.llm.dto.ExtractionResult;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 검색/생성 의도 분류의 결정론적 프리라우터.
 *
 * <p>모든 채팅 메시지가 {@link KeywordExtractor} 를 통해 Grok 으로 분류되던 것을, <b>명확한 기능 신호</b>는
 * LLM 콜 없이 룰로 먼저 분류해 LLM 콜·latency·비용을 줄인다. 설계·결정 근거:
 * {@code docs/decisions/S1A-rule-prerouter-design.md} (ADR §4 하이브리드 분류의 구현).
 *
 * <p><b>1차 범위 = TERMINAL 케이스만</b>. 즉 룰이 매치하면 LLM 콜을 실제로 0 으로 만드는 신호에만 발화한다:
 * <ul>
 *   <li>{@code SKIP} — 인사·감사·확인 같은 짧은 단독 표현 (LLM 답변까지 생략)</li>
 *   <li>{@code GENERATE_NOW} — 명시적 생성 동사 ("그려줘", "만들어줘" 등). 사용자 원문을 그대로 담는다.
 *       {@code ImageGenerationService.generate()} 가 {@code PromptTranslator} 로 번역하므로 영문 프롬프트 불필요.</li>
 * </ul>
 *
 * <p>{@code NEW_SEARCH}/{@code [N]번 앵커}는 어차피 검색 키워드 추출을 위해 Grok 을 호출해야 해 1차 이득이 적고
 * 오분류 위험만 늘어, 의도적으로 룰에서 제외한다(MISS 로 흘려 기존 Grok 경로 유지). 2차에서 재검토.
 *
 * <p>매치 실패 시 {@link Decision#miss()} → 호출 측이 기존 Grok 풀 분류로 폴백한다. 즉 이 라우터는
 * 기존 동작 위에 "공짜로 얹는" 레이어이며 회귀 위험이 없다.
 */
@Slf4j
@Component
public class RulePreRouter {

  /**
   * 룰 판정 결과. {@code result} 가 null 이면 MISS(룰 미스 → LLM 폴백), non-null 이면 TERMINAL(룰이 끝까지 결정).
   *
   * @param ruleId 발화한 룰 식별자 (메트릭·디버깅용). MISS 면 {@code "miss"}.
   * @param result TERMINAL 시 확정된 {@link ExtractionResult}, MISS 면 null.
   */
  public record Decision(String ruleId, ExtractionResult result) {

    public boolean isHit() {
      return result != null;
    }

    static Decision hit(String ruleId, ExtractionResult result) {
      return new Decision(ruleId, result);
    }

    static Decision miss() {
      return new Decision("miss", null);
    }
  }

  // ── 생성 동사 ──────────────────────────────────────
  // 두 갈래로 구분한다:
  //   (A) 요청 어미 동반 — "그려줘", "만들어줄래?", "생성해주세요", "제작해 줘"
  //       → 어미(줘/줄래/주라/주세요/봐줘/줄 수)가 명백한 생성 요청. 물음표가 와도 요청("그려줄래?").
  //   (B) 어간 단독 종결 — "AI로 만들어", "한번 그려"
  //       → 어미 없이 평서 종결. 단 물음표로 끝나면 방법 질문("그려?", "만들어?")이라 제외.
  // "해"는 한자어 어간(생성/제작)의 동사화: 생성"해"줘.
  // 영어 명령형도 포함.
  private static final Pattern GENERATE =
      Pattern.compile(
          // (A) 어미 동반 — 물음표 허용
          "(그려|그리|만들어|만들|생성|제작)\\s*(해)?\\s*(줘|줄래|주라|주세요|봐줘|줄\\s*수)"
              // (B) 어간 단독 종결 — 물음표 아닐 때만 (lookahead 에서 ? 제외)
              + "|(그려|만들어|생성|제작)\\s*(해)?(?=[\\s!.~,]|$)"
              + "|\\b(generate|make it|draw it|create an image|create a picture)\\b",
          Pattern.CASE_INSENSITIVE);

  // 방법·기법 질문 가드: 이 의문 신호가 있으면 생성 요청이 아니라 "어떻게 그려?" 류 → MISS(KEEP 후보).
  private static final Pattern HOW_QUESTION =
      Pattern.compile("어떻게|어떡|어케|어찌|방법|어떤\\s*식|how\\s+(to|do|can)", Pattern.CASE_INSENSITIVE);

  // ── 인사·감사·확인 (단독성 검사와 함께 사용) ──────────
  // 메시지가 짧고 이 신호가 사실상 전부일 때만 SKIP. "이 구도 좋아 보이는데?" 같은 문장은 길이로 걸러진다.
  // 어간 + 흔한 종결어미("합니다/해요/했어/네")를 폭넓게 받는다.
  private static final Pattern THANKS_GREETING =
      Pattern.compile(
          "^(고마워|고맙|감사|ㄳ|ㄱㅅ|땡큐|thank|thanks|thx"
              + "|안녕|하이|hi|hello"
              + "|ㅇㅋ|오케이|ok|okay|굿|good|좋아|좋네|잘했어|훌륭|최고|ㅋㅋ+|ㅎㅎ+|ㅇㅇ)"
              + "(합니다|해요|했어요|했어|네요|네|요|당)?"
              + "[\\s!.~,]*$",
          Pattern.CASE_INSENSITIVE);

  /** SKIP 룰의 단독성 임계: 이 길이를 넘으면 인사/감사 토큰이 있어도 문장으로 보고 룰을 발화하지 않는다. */
  private static final int SKIP_MAX_LENGTH = 10;

  // ── 작업물 비평 요청 (010 SELF_CRITIQUE) ───────────────
  // "이거 어때?", "평가해줘", "피드백 주세요", "봐줄래?", "고칠 점 있어?", "잘 그렸어?" 류.
  // 이미지 유무는 여기서 모른다 — 호출 측(ChatLlmService)이 hasImage 와 AND 로 결합해 010 을 확정한다.
  // 즉 이 신호 단독으로는 010 이 아니다(이미지 없이 "어때?"는 일반 대화일 수 있음). 설계 §2.2.
  private static final Pattern CRITIQUE_REQUEST =
      Pattern.compile(
          "어때|어떄|어떤\\s*것?\\s*같|평가|피드백|봐\\s*줄?|봐\\s*주|고칠\\s*점|고칠\\s*부분"
              + "|잘\\s*(그렸|됐|했)|괜찮(아|나|은가|을까)|어떻게\\s*보(여|이)"
              + "|critique|feedback|review|how('?s|\\s+is)\\s+(this|it|my)",
          Pattern.CASE_INSENSITIVE);

  // ── 도메인 외 질문 (000 OUT_OF_DOMAIN) ─────────────────
  // 명백히 그림과 무관한 도메인 신호. 오탐(미술 관련인데 거절)이 거절 UX 를 크게 해치므로 매우 보수적으로,
  // "이건 누가 봐도 비미술" 인 강한 신호만 발화한다. 약하면 MISS → 기존 경로(페르소나가 거절 톤 처리)로 흘린다.
  private static final Pattern OUT_OF_DOMAIN =
      Pattern.compile(
          "날씨|기온|미세먼지|뉴스|주가|주식|코인|비트코인|환율|정치|선거|대통령"
              + "|코딩|프로그래밍|자바|파이썬|컴파일|맛집|요리|레시피|식당|메뉴"
              + "|축구|야구|경기\\s*결과|로또|수학\\s*문제|방정식|번역(?!체)"
              + "|병원|약\\s*추천|진단|법률|변호사|소송",
          Pattern.CASE_INSENSITIVE);

  // 그림과 연결될 수 있는 신호(있으면 000 으로 단정하지 않는다). 예: "노을 색 그리려는데 날씨 표현 어떻게?"
  // → 그림 맥락이라 거절 대상 아님. OUT_OF_DOMAIN 과 동시에 잡히면 MISS 로 양보(보수성).
  private static final Pattern ART_CONTEXT =
      Pattern.compile(
          "그림|그리|그려|드로잉|스케치|소묘|일러스트|작화|작업물|시안|구도|채색|색감|명암|음영"
              + "|레퍼런스|참고\\s*이미지|기법|수채|유화|디지털|크로키|데생|화풍|붓|팔레트"
              + "|draw|sketch|paint|illustration|reference|composition",
          Pattern.CASE_INSENSITIVE);

  /**
   * 메시지를 룰로 분류한다. {@code history} 는 1차 룰에서는 쓰지 않지만(앵커/NEW_SEARCH 2차용) 시그니처는 유지한다.
   *
   * @param userMessage 사용자 원문 메시지
   * @return TERMINAL {@link Decision} 또는 {@link Decision#miss()}
   */
  public Decision route(String userMessage, List<?> history) {
    if (userMessage == null || userMessage.isBlank()) {
      // 빈 메시지는 기존 extract 가 SKIP 처리하지만, 여기서도 명시적으로 SKIP.
      return Decision.hit("empty", ExtractionResult.skip());
    }

    String trimmed = userMessage.trim();

    // 방법·기법 질문은 생성 요청처럼 보여도 KEEP 후보 → 룰로 단정하지 않고 LLM 에 맡긴다.
    // 예: "이거 어떻게 그려?" 의 "그려"가 생성으로 오발화하는 것을 막는다.
    boolean isHowQuestion = HOW_QUESTION.matcher(trimmed).find();

    // 우선순위 1: 생성 동사 > 검색 신호.
    // "비슷한 거 만들어줘" 가 NEW_SEARCH 가 아니라 GENERATE_NOW 로 가도록 생성부터 검사.
    if (!isHowQuestion && GENERATE.matcher(trimmed).find()) {
      log.info("룰 매치: GENERATE_NOW (rule=generate_verb)");
      // 원문 그대로 전달 — PromptTranslator 가 영문 프롬프트로 번역.
      return Decision.hit("generate_verb", ExtractionResult.generateNow(trimmed));
    }

    // 우선순위 2: 짧은 단독 인사·감사 → SKIP.
    if (trimmed.length() <= SKIP_MAX_LENGTH && THANKS_GREETING.matcher(trimmed).find()) {
      log.info("룰 매치: SKIP (rule=thanks_greeting)");
      return Decision.hit("thanks_greeting", ExtractionResult.skip());
    }

    // 그 외 전부 MISS → 기존 Grok 풀 분류 (NEW_SEARCH/KEEP/앵커/미술의도 등).
    return Decision.miss();
  }

  /**
   * 메시지가 "작업물 비평 요청" 신호를 담고 있는지 (010 SELF_CRITIQUE 후보). 결정론적·LLM 콜 0.
   *
   * <p><b>이것만으로는 010 이 아니다.</b> 이미지 없이 "어때?"는 일반 대화일 수 있으므로, 호출 측이
   * {@code hasUploadedImage && isCritiqueRequest(message)} 로 결합해야 010 을 확정한다(설계 §2.2). 신호가
   * 약하면 false → 기존 분류 경로로 흘려보낸다(회귀 없음).
   */
  public boolean isCritiqueRequest(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) {
      return false;
    }
    return CRITIQUE_REQUEST.matcher(userMessage).find();
  }

  /**
   * 메시지가 "명백히 그림과 무관한 도메인 외 질문" 인지 (000 OUT_OF_DOMAIN 후보). 결정론적·LLM 콜 0.
   *
   * <p><b>매우 보수적</b>: 오탐(미술 관련인데 거절)이 거절 UX 를 크게 해치므로, 강한 비미술 신호가 있고 <b>그림 맥락
   * 신호가 없을 때만</b> true. 둘 다 잡히면(예: "노을 색 그리는데 날씨 표현은?") 그림 맥락에 양보해 false →
   * 기존 분류 경로로 흘린다(페르소나 v2 가 도메인 락으로 거절 톤을 처리하므로 안전). 설계 §000.
   */
  public boolean isOutOfDomain(String userMessage) {
    if (userMessage == null || userMessage.isBlank()) {
      return false;
    }
    return OUT_OF_DOMAIN.matcher(userMessage).find() && !ART_CONTEXT.matcher(userMessage).find();
  }
}
