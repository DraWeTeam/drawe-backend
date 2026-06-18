package com.drawe.backend.domain.llm.classifier;

import com.drawe.backend.domain.llm.contract.IntentCode;
import com.drawe.backend.domain.llm.contract.IntentResult;
import com.drawe.backend.domain.llm.dto.ExtractionResult;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 기존 분류 결과({@link ExtractionResult} 4 Action)를 contract {@link IntentResult}(code, tier)로 변환하는 어댑터
 * (트랙 A ②-1차). WorkflowService 가 소비하는 타입을 만든다.
 *
 * <p>순수 매핑이라 LLM 콜이 없다. 설계: {@code docs/decisions/S1A-intent-classifier-design.md}.
 *
 * <p><b>tier 판정</b>: 룰({@code RulePreRouter})이 결정했으면 {@link IntentResult.Tier#RULE}, Grok 풀 분류가
 * 결정했으면 {@link IntentResult.Tier#LLM_LIGHT}. 호출 측이 어느 경로였는지 {@code ruleDecided} 로 알려준다.
 *
 * <p><b>KEEP</b>: 현재는 {@link IntentCode#KEEP}(006)로 매핑한다. 미술 의도 세분류(001 구도/002 빛/003 색/004
 * 기법)는 {@code KeywordExtractor} 응답 확장이 필요한 ②-2차 작업이다(설계 §2·§4). 그전까지 KEEP 은 미분류 유지.
 */
@Component
public class IntentResultAdapter {

  /**
   * @param decision 최종 분류 결과 (룰 또는 Grok 산출)
   * @param ruleDecided 룰({@code RulePreRouter})이 결정했으면 true → tier=RULE. Grok 폴백이면 false → LLM_LIGHT.
   * @param referencedImages 앵커 슬롯 ("[2]번" → [2]). 없으면 빈 리스트.
   * @param hasUploadedImage 사용자가 본인 작업물을 업로드했는지 (010 트리거 정보, 슬롯 전달용).
   */
  public IntentResult adapt(
      ExtractionResult decision,
      boolean ruleDecided,
      List<Integer> referencedImages,
      boolean hasUploadedImage) {
    IntentResult.Tier tier = ruleDecided ? IntentResult.Tier.RULE : IntentResult.Tier.LLM_LIGHT;
    IntentCode code = toCode(decision);
    return new IntentResult(
        code, referencedImages == null ? List.of() : referencedImages, hasUploadedImage, tier);
  }

  /**
   * 010 SELF_CRITIQUE 전용 산출 (S3' 트랙 A ②, 설계 §3.2 방식 (나)). {@code ExtractionResult}(4 Action)에는
   * 비평 의도가 없어 {@link #adapt} 로는 010 을 만들 수 없다. 그래서 레거시 4-Action 분류 타입을 건드리지 않고
   * (회귀면 0) 여기서 직접 IntentResult 를 만든다. 010 은 live 워크플로에서만 도달하므로(레거시는 멀티모달 비평
   * 미지원) 이 경로로 충분하다.
   *
   * <p>트리거는 호출 측({@code ChatLlmService})이 {@code hasUploadedImage && RulePreRouter.isCritiqueRequest}
   * 로 결정론적으로 확정한 뒤 호출한다 — 그래서 tier 는 항상 {@link IntentResult.Tier#RULE} 다.
   *
   * @param referencedImages 앵커 슬롯. 비평엔 보통 없으나 시그니처 일관성 위해 받는다(null → 빈 리스트).
   */
  public IntentResult adaptSelfCritique(List<Integer> referencedImages) {
    return new IntentResult(
        IntentCode.SELF_CRITIQUE,
        referencedImages == null ? List.of() : referencedImages,
        true, // 010 은 정의상 업로드 이미지가 있을 때만 확정됨
        IntentResult.Tier.RULE);
  }

  /**
   * 000 OUT_OF_DOMAIN 전용 산출 (S3' 트랙 A, 설계 §000 — adaptSelfCritique 와 동일한 방식 (나)).
   * {@code ExtractionResult} 4 Action 에 도메인 외 의도가 없어 {@link #adapt} 로는 못 만든다. 호출 측이
   * {@code RulePreRouter.isOutOfDomain} 으로 결정론적으로 확정한 뒤 호출하므로 tier 는 항상 {@code RULE}.
   */
  public IntentResult adaptOutOfDomain() {
    return new IntentResult(IntentCode.OUT_OF_DOMAIN, List.of(), false, IntentResult.Tier.RULE);
  }

  /**
   * 4 Action → IntentCode. KEEP 은 미술 의도 세분류({@code artIntent})가 있으면 001~004, 없으면 006(미분류) 으로
   * 매핑한다 (②-2차). 룰이 결정한 KEEP 은 artIntent 가 없으니 자연히 006.
   */
  private IntentCode toCode(ExtractionResult decision) {
    return switch (decision.action()) {
      case NEW_SEARCH -> IntentCode.NEW_SEARCH; // 005
      case KEEP -> decision.artIntent() != null ? decision.artIntent() : IntentCode.KEEP; // 001~004 or 006
      case SKIP -> IntentCode.SKIP; // 007
      case GENERATE_NOW -> IntentCode.GENERATE; // 008
      case FOLLOWUP -> IntentCode.FOLLOWUP; // 012
      case COMPARE -> IntentCode.COMPARE; // 013
    };
  }
}
