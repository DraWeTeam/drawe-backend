package com.drawe.backend.domain.llm.search;

import com.drawe.backend.domain.enums.MessageRole;
import com.drawe.backend.domain.llm.dto.LlmCallContext;
import com.drawe.backend.domain.llm.dto.LlmCallResult;
import com.drawe.backend.domain.llm.service.GrokService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * {@link KeywordExtractorFallback} 의 Grok(xAI) 구현 — 사전 미스율 초과 시 LLM 키워드 재추출.
 *
 * <p>{@link KomoranKeywordExtractor} 가 사전 미스율 30% 초과를 감지하면 호출. 형태소·사전으로
 * 못 잡은 단어(예: "역동적"=dynamic, 신조어, 사전 미등록 미술 용어)를 Grok 이 문장 컨텍스트를 보고
 * 영문 검색 키워드로 재추출한다. {@code NoopKeywordExtractorFallback}(빈값) 의 실연결 대체.
 *
 * <p><b>등록 효과</b>: 이 빈이 컨텍스트에 올라오면 {@code NoopKeywordExtractorFallback} 의
 * {@code @ConditionalOnMissingBean(KeywordExtractorFallback.class)} 조건이 깨져 Noop 은
 * 비활성화된다. (부팅 시 활성 fallback 이 이 구현체인지 통합에서 확인 권장.)
 *
 * <p><b>장애 격리</b>: Grok 호출/파싱 실패 시 인터페이스 계약대로 빈 리스트를 반환한다(null 금지).
 * 검색 결과는 0건이 되지만 요청 흐름은 깨지지 않는다.
 *
 * <p>TODO (후속): ① Structured Output(JSON 스키마) 네이티브 강제 — 현재는 프롬프트 지시 + 관용
 * 파싱. ② Resilience4j 타임아웃·서킷브레이커 적용 (ADR §8). ③ 프롬프트 튜닝 (베타 로그 기반).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GrokKeywordExtractorFallback implements KeywordExtractorFallback {

  /** 반환 키워드 상한 — 과도한 키워드로 검색이 흐려지는 것 방지. */
  private static final int MAX_KEYWORDS = 6;

  private static final String SYSTEM_PROMPT =
      """
      너는 한국어 미술/그림 레퍼런스 검색 질의에서 영문 검색 키워드를 뽑는 추출기다.
      입력 문장에서 이미지 검색에 쓸 핵심 영문 키워드만 골라 JSON 문자열 배열로 반환하라.

      규칙:
      - subject(고양이=cat), technique(수채화=watercolor), mood, pose, lighting 등 시각 요소 위주
      - "찾아줘", "보여줘", "만들어줘" 같은 요청/생성 동사는 키워드에서 제외
      - 최대 %d개, 모두 소문자 영어 단어/구
      - 반드시 JSON 배열만 출력. 설명·코드펜스 없이. 예: ["dynamic","cat","pose"]
      """
          .formatted(MAX_KEYWORDS);

  private final GrokService grokService;
  private final ObjectMapper objectMapper;

  @Override
  public List<String> extract(String cleanedMessage) {
    if (cleanedMessage == null || cleanedMessage.isBlank()) {
      return List.of();
    }
    try {
      LlmCallContext ctx =
          new LlmCallContext(
              List.of(new LlmCallContext.Turn(MessageRole.SYSTEM, SYSTEM_PROMPT)),
              cleanedMessage,
              null,
              null);
      LlmCallResult result = grokService.generate(ctx);
      List<String> keywords = parseKeywords(result.content());
      if (log.isDebugEnabled()) {
        log.debug("Grok fallback: '{}' → {}", cleanedMessage, keywords);
      }
      return keywords;
    } catch (Exception e) {
      // 인터페이스 계약: 실패 시 빈 리스트(null 금지). 검색은 0건이 되지만 요청은 안 깨짐.
      log.warn("Grok fallback 실패 — 빈 키워드 반환: error_class={}", e.getClass().getSimpleName());
      return List.of();
    }
  }

  /** Grok 응답(JSON 배열, 코드펜스·설명 섞일 수 있음)에서 키워드 추출. 실패 시 빈 리스트. */
  private List<String> parseKeywords(String content) {
    if (content == null || content.isBlank()) {
      return List.of();
    }
    try {
      String[] arr = objectMapper.readValue(extractJsonArray(content), String[].class);
      return Arrays.stream(arr)
          .filter(s -> s != null && !s.isBlank())
          .map(s -> s.trim().toLowerCase())
          .distinct()
          .limit(MAX_KEYWORDS)
          .toList();
    } catch (Exception e) {
      log.warn("Grok fallback 응답 파싱 실패: error_class={}", e.getClass().getSimpleName());
      return List.of();
    }
  }

  /** 코드펜스/설명이 섞여도 첫 '[' ~ 마지막 ']' 구간만 잘라 JSON 배열로 시도. */
  private String extractJsonArray(String content) {
    int start = content.indexOf('[');
    int end = content.lastIndexOf(']');
    if (start >= 0 && end > start) {
      return content.substring(start, end + 1);
    }
    return content;
  }
}