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
 * <p><b>KEEP</b>: 현재는 {@link IntentCode#KEEP}(006)로 매핑한다. 미술 의도 세분류(001 구도/002 빛/003 색/004 기법)는
 * {@code KeywordExtractor} 응답 확장이 필요한 ②-2차 작업이다(설계 §2·§4). 그전까지 KEEP 은 미분류 유지.
 */
@Component
public class IntentResultAdapter {

  /**
   * @param decision 최종 분류 결과 (룰 또는 Grok 산출)
   * @param ruleDecided 룰({@code RulePreRouter})이 결정했으면 true → tier=RULE. Grok 폴백이면 false →
   *     LLM_LIGHT.
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
   * 4 Action → IntentCode. KEEP 은 미술 의도 세분류({@code artIntent})가 있으면 001~004, 없으면 006(미분류) 으로 매핑한다
   * (②-2차). 룰이 결정한 KEEP 은 artIntent 가 없으니 자연히 006.
   */
  private IntentCode toCode(ExtractionResult decision) {
    return switch (decision.action()) {
      case NEW_SEARCH -> IntentCode.NEW_SEARCH; // 005
      case KEEP ->
          decision.artIntent() != null ? decision.artIntent() : IntentCode.KEEP; // 001~004 or 006
      case SKIP -> IntentCode.SKIP; // 007
      case GENERATE_NOW -> IntentCode.GENERATE; // 008
    };
  }
}
