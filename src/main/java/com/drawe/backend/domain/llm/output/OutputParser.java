package com.drawe.backend.domain.llm.output;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Grok structured content(JSON 문자열) → {@link ComposedOutput} 파서(설계 §5·§6.2).
 *
 * <p>입력은 {@code GrokService} 가 {@code response_format:json_schema} 로 강제한
 * {@code {message,citations,offer_generate}} JSON 문자열이다. strict 스키마로 거의 깨지지 않지만,
 * 폴백을 보증한다 — JSON 파싱 실패 시 <b>원본을 노출하지 않고</b> 결정론적 안전 템플릿으로 평문화한다(§6.2).
 * 재호출은 하지 않는다(ADR §6).
 *
 * <p>무결성(환각 인용 제거)은 이 클래스가 아니라 {@link OutputIntegrityChecker} 의 책임이다.
 * 여기서는 JSON → DTO 변환과 깨진 JSON 폴백만 한다.
 */
@Slf4j
@Component
public class OutputParser {

  /** 깨진 JSON 폴백 메시지(설계 §6.2). 원본 노출 금지 — 결정론적 템플릿으로 대체. */
  public static final String BROKEN_JSON_FALLBACK_MESSAGE =
      "조언을 정리하다 문제가 생겼어요. 다시 한 번 말씀해 주실래요?";

  private final ObjectMapper objectMapper;

  public OutputParser(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /**
   * structured content 를 파싱한다. JSON 이 깨졌거나 {@code message} 가 없으면 안전 템플릿 + 빈 인용을 반환한다.
   * 인용 무결성(유효 범위 검사·환각 제거)은 호출자가 {@link OutputIntegrityChecker} 로 별도 수행한다.
   *
   * @param content Grok 응답 content(JSON 문자열). null/blank 도 폴백.
   * @return 파싱 결과. 폴백 시 {@code message=}{@link #BROKEN_JSON_FALLBACK_MESSAGE}, 빈 citations, offerGenerate=false.
   */
  public ComposedOutput parse(String content) {
    return parseWithSignal(content).output();
  }

  /**
   * {@link #parse} 와 동일하게 파싱하되, 안전 템플릿 폴백이 일어났는지({@code brokenJson})를 함께 돌려준다.
   * ⑦ 의 {@code drawe.output.structure_violation{reason=json_broke}} 카운터를 발사하려면 호출자(ComposeExecutor)가
   * 폴백 여부를 알아야 하기 때문이다. 본문 텍스트 비교 같은 취약한 우회 없이 명시적 신호를 준다.
   */
  public ParsedOutput parseWithSignal(String content) {
    if (content == null || content.isBlank()) {
      log.warn("COMPOSE content 가 비어있음 — 안전 템플릿 폴백");
      return new ParsedOutput(fallback(), true);
    }

    JsonNode root;
    try {
      root = objectMapper.readTree(content);
    } catch (Exception e) {
      log.warn("COMPOSE content JSON 파싱 실패 — 안전 템플릿 폴백: error_class={}", e.getClass().getSimpleName());
      return new ParsedOutput(fallback(), true);
    }

    JsonNode messageNode = root.get("message");
    if (messageNode == null || !messageNode.isTextual() || messageNode.asText().isBlank()) {
      log.warn("COMPOSE content 에 message 가 없거나 비어있음 — 안전 템플릿 폴백");
      return new ParsedOutput(fallback(), true);
    }

    ComposedOutput parsed =
        new ComposedOutput(
            messageNode.asText(),
            readCitations(root.get("citations")),
            readBool(root.get("offer_generate")));
    return new ParsedOutput(parsed, false);
  }

  /**
   * 파싱 결과 + 폴백 여부. {@code brokenJson=true} 면 {@link #BROKEN_JSON_FALLBACK_MESSAGE} 안전 템플릿으로
   * 평문화됐다는 뜻 — ⑦ 구조 위반 메트릭(reason=json_broke)의 입력.
   */
  public record ParsedOutput(ComposedOutput output, boolean brokenJson) {}

  /** citations 배열에서 정수만 추출. 누락/비배열은 빈 리스트. 비정수 원소는 무시(무결성 검사가 범위까지 본다). */
  private List<Integer> readCitations(JsonNode node) {
    List<Integer> out = new ArrayList<>();
    if (node != null && node.isArray()) {
      for (JsonNode el : node) {
        if (el.isInt()) {
          out.add(el.asInt());
        }
      }
    }
    return out;
  }

  private boolean readBool(JsonNode node) {
    return node != null && node.isBoolean() && node.asBoolean();
  }

  private ComposedOutput fallback() {
    return new ComposedOutput(BROKEN_JSON_FALLBACK_MESSAGE, List.of(), false);
  }
}
